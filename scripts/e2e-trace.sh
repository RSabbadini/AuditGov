#!/usr/bin/env bash
#
# e2e-trace.sh - prova de propagacao entre DOIS servicos: fachada (8081) -> servico de cidadaos (8080).
#
# Faz UMA chamada a fachada, com um trace_id escolhido aqui, e confere com esse mesmo trace_id:
#   1. a resposta da fachada (HTTP 200);
#   2. a evidencia gravada em audit_log pelo servico de cidadaos (mesmo trace_id e operador do token);
#   3. o trace no Jaeger: UM trace com spans dos DOIS servicos (auditgov-facade e auditgov).
# O codigo de saida e 0 so se as tres conferencias passarem. A saida e a evidencia para o relatorio
# (copie-a como esta; nao a edite).
#
# Pre-requisitos (veja facade/README.md):
#   docker compose up -d          # PostgreSQL + Jaeger
#   ./mvnw quarkus:dev            # servico de cidadaos, porta 8080 (raiz do projeto)
#   cd facade && ../mvnw quarkus:dev -Ddebug=false    # fachada, porta 8081
#
# Uso:
#   ./scripts/e2e-trace.sh [operador] [cpf]
#   ./scripts/e2e-trace.sh fiscal_maria 12345678900
#
# Variaveis opcionais: FACHADA_URL (http://localhost:8081), JAEGER_URL (http://localhost:16686),
# PG_CONTAINER (auditgov-postgres), SERVICO_FACHADA (auditgov-facade), SERVICO_PRINCIPAL (auditgov),
# JAEGER_TENTATIVAS (30, uma por segundo) e DOCKER_CMD (padrao "sudo docker", como no test-audit.sh;
# use "docker" se seu usuario ja esta no grupo docker).
#
# Os spans sao exportados em lote (a cada ~5 s); o script tenta ate JAEGER_TENTATIVAS segundos ate o
# Jaeger mostrar o trace.
set -uo pipefail

FACHADA="${FACHADA_URL:-http://localhost:8081}"
JAEGER="${JAEGER_URL:-http://localhost:16686}"
PG="${PG_CONTAINER:-auditgov-postgres}"
DOCKER="${DOCKER_CMD:-sudo docker}"
SERVICO_FACHADA="${SERVICO_FACHADA:-auditgov-facade}"
SERVICO_PRINCIPAL="${SERVICO_PRINCIPAL:-auditgov}"
TENTATIVAS="${JAEGER_TENTATIVAS:-30}"
OPERADOR="${1:-fiscal_maria}"
CPF="${2:-12345678900}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for CMD in curl openssl docker; do
  command -v "$CMD" >/dev/null || { echo "$CMD nao encontrado." >&2; exit 2; }
done
[[ "$CPF" =~ ^[0-9]{11}$ ]] || { echo "CPF invalido: informe 11 digitos." >&2; exit 2; }
[[ "$TENTATIVAS" =~ ^[0-9]+$ ]] || { echo "JAEGER_TENTATIVAS invalido: informe um inteiro." >&2; exit 2; }

TOKEN="$("$SCRIPT_DIR/mint-token.sh" "$OPERADOR")" || { echo "Falha ao emitir o token." >&2; exit 2; }

TRACE_ID="$(openssl rand -hex 16)"
SPAN_ID="$(openssl rand -hex 8)"
FALHAS=0
RESPOSTA="$(mktemp)"
trap 'rm -f "$RESPOSTA"' EXIT

echo "== Prova de propagacao entre dois servicos"
echo "   data/hora (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "   operador (claim upn do token): $OPERADOR"
echo "   trace_id escolhido: $TRACE_ID"
echo

# ---------------------------------------------------------------- 1. fachada
echo "== 1. Chamada a fachada: POST $FACHADA/api/v1/portal/consulta"
HTTP="$(curl -s -o "$RESPOSTA" -w '%{http_code}' -X POST "$FACHADA/api/v1/portal/consulta" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-$TRACE_ID-$SPAN_ID-01" \
  -d "{\"cpf\":\"$CPF\"}" || true)"
echo "   HTTP $HTTP (o corpo da resposta nao e impresso)"
if [ "$HTTP" = "200" ]; then
  echo "   [OK] a fachada respondeu 200"
else
  echo "   [FALHOU] esperado 200"
  FALHAS=$((FALHAS + 1))
fi
echo

# ---------------------------------------------------------------- 2. audit_log
echo "== 2. Evidencia em audit_log (servico de cidadaos) com o mesmo trace_id"
# shellcheck disable=SC2086
LINHAS="$($DOCKER exec "$PG" psql -U audit_user -d auditgov_db -At -F ' | ' \
  -c "select seq, operator_id, action, resource from audit_log where trace_id = '$TRACE_ID' order by seq" 2>&1)"
STATUS_PSQL=$?
echo "   seq | operator_id | action | resource"
if [ "$STATUS_PSQL" -ne 0 ]; then
  echo "   [FALHOU] nao consegui consultar o banco: $LINHAS"
  FALHAS=$((FALHAS + 1))
elif [ -z "$LINHAS" ]; then
  echo "   (nenhuma linha) [FALHOU] o servico de cidadaos nao gravou evidencia com este trace_id"
  FALHAS=$((FALHAS + 1))
else
  printf '%s\n' "$LINHAS" | sed 's/^/   /'
  if printf '%s\n' "$LINHAS" | grep -qF " | $OPERADOR | "; then
    echo "   [OK] ha evidencia com este trace_id e o operador e o do token ($OPERADOR)"
  else
    echo "   [FALHOU] ha linha com este trace_id, mas o operador nao e $OPERADOR"
    FALHAS=$((FALHAS + 1))
  fi
fi
echo

# ---------------------------------------------------------------- 3. Jaeger
echo "== 3. Trace no Jaeger: GET $JAEGER/api/traces/$TRACE_ID"
SERVICOS=""
OPERACOES=""
ACHOU=0
for TENTATIVA in $(seq 1 "$TENTATIVAS"); do
  JSON="$(curl -s "$JAEGER/api/traces/$TRACE_ID" || true)"
  SERVICOS="$(printf '%s' "$JSON" | grep -oE '"serviceName" *: *"[^"]*"' | sed -E 's/.*: *"([^"]*)"/\1/' | sort -u)"
  if printf '%s\n' "$SERVICOS" | grep -qx "$SERVICO_FACHADA" && printf '%s\n' "$SERVICOS" | grep -qx "$SERVICO_PRINCIPAL"; then
    OPERACOES="$(printf '%s' "$JSON" | grep -oE '"operationName" *: *"[^"]*"' | sed -E 's/.*: *"([^"]*)"/\1/' | sort -u)"
    ACHOU=1
    break
  fi
  sleep 1
done
echo "   servicos encontrados neste trace:"
if [ -n "$SERVICOS" ]; then printf '%s\n' "$SERVICOS" | sed 's/^/     - /'; else echo "     (nenhum)"; fi
if [ "$ACHOU" -eq 1 ]; then
  echo "   operacoes (spans) neste trace:"
  printf '%s\n' "$OPERACOES" | sed 's/^/     - /'
  echo "   [OK] UM trace com spans dos dois servicos ($SERVICO_FACHADA e $SERVICO_PRINCIPAL)"
else
  echo "   [FALHOU] o Jaeger nao mostrou spans dos dois servicos neste trace em $TENTATIVAS s"
  FALHAS=$((FALHAS + 1))
fi
echo "   Abra no navegador: $JAEGER/trace/$TRACE_ID"
echo

# ---------------------------------------------------------------- resultado
if [ "$FALHAS" -eq 0 ]; then
  echo "RESULTADO: OK - um trace_id ($TRACE_ID) liga a fachada, o servico de cidadaos, o audit_log e o Jaeger."
  exit 0
fi
echo "RESULTADO: FALHOU ($FALHAS conferencia(s) sem sucesso). Nao use esta saida como evidencia de sucesso."
exit 1
