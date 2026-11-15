#!/usr/bin/env bash
#
# test-audit.sh - roteiro de validação do AuditGov em um único script.
#
# Emite um token JWT de laboratório (scripts/mint-token.sh), faz uma requisição ao endpoint
# auditado (POST, com o CPF no corpo), confere que a mesma requisição SEM token é recusada (401) e
# não gera evidência, consulta a evidência gravada no PostgreSQL (incluindo o encadeamento de hash),
# confere que o CPF não aparece na trilha nem no rastro do Jaeger, verifica a cadeia de hash pelo
# endpoint do auditor, tenta alterar a evidência (o banco deve recusar) e imprime o link do trace
# no Jaeger para a conferência manual do batimento trace_id <-> payload_hash.
#
# Uso:
#   ./test-audit.sh                  # usa valores padrão
#   ./test-audit.sh 98765432100      # testa outro CPF fictício
#   ./test-audit.sh 00000000000      # testa o caso de falha (404)
#
# Variáveis opcionais: APP_HOST, JAEGER_UI, OPERATOR, PG_CONTAINER, PG_USER, PG_DB, DOCKER_CMD
# e AUDITGOV_HMAC_KEY (para conferir o subject_ref; sem ela usa a chave de desenvolvimento).
# OPERATOR é o operador que vai na claim "upn" do token (padrão fiscal_maria).
#
set -euo pipefail

# ------------------------------------------------------------------
# Configuração
# ------------------------------------------------------------------
APP_HOST="${APP_HOST:-http://localhost:8080}"
JAEGER_UI="${JAEGER_UI:-http://localhost:16686}"
OPERATOR="${OPERATOR:-fiscal_maria}"
CPF="${1:-12345678900}"

PG_CONTAINER="${PG_CONTAINER:-auditgov-postgres}"
PG_USER="${PG_USER:-audit_user}"
PG_DB="${PG_DB:-auditgov_db}"

DOCKER="${DOCKER_CMD:-sudo docker}"  # troque para "docker" se seu usuário já está no grupo docker

# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------
bold()  { printf '\033[1m%s\033[0m\n' "$1"; }
ok()    { printf '  \033[32m✔\033[0m %s\n' "$1"; }
fail()  { printf '  \033[31m✘\033[0m %s\n' "$1"; }
info()  { printf '  %s\n' "$1"; }

command -v curl >/dev/null || { echo "curl não encontrado. Instale antes de continuar."; exit 1; }
command -v openssl >/dev/null || { echo "openssl não encontrado. Instale antes de continuar."; exit 1; }
HAS_JQ=0
command -v jq >/dev/null && HAS_JQ=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ------------------------------------------------------------------
# Passo 1 - Requisição com token JWT e traceId próprio (o CPF vai no corpo, não na URL)
# ------------------------------------------------------------------
bold "1. Requisição HTTP autenticada"

TOKEN=$("$SCRIPT_DIR/scripts/mint-token.sh" "$OPERATOR")
TRACE_ID=$(openssl rand -hex 16)
SPAN_ID=$(openssl rand -hex 8)
info "TraceID gerado: $TRACE_ID"
info "Endpoint: POST $APP_HOST/api/v1/cidadaos/consulta (CPF no corpo da requisição)"
info "Operador (claim upn do token JWT de laboratório): $OPERATOR"
echo

HTTP_BODY_FILE=$(mktemp)
HTTP_STATUS=$(curl -s -o "$HTTP_BODY_FILE" -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "traceparent: 00-${TRACE_ID}-${SPAN_ID}-01" \
  -H "Accept: application/json" \
  -d "{\"cpf\":\"${CPF}\"}" \
  "$APP_HOST/api/v1/cidadaos/consulta")

if [ "$HTTP_STATUS" = "200" ]; then
    ok "HTTP $HTTP_STATUS"
else
    fail "HTTP $HTTP_STATUS (esperado 200; se você passou um CPF inexistente de propósito, 404 é o comportamento correto)"
fi

if [ "$HAS_JQ" = "1" ]; then
    jq . "$HTTP_BODY_FILE" 2>/dev/null | sed 's/^/  /' || sed 's/^/  /' "$HTTP_BODY_FILE"
else
    sed 's/^/  /' "$HTTP_BODY_FILE"
fi
echo

# ------------------------------------------------------------------
# Passo 2 - A mesma requisição SEM token deve ser recusada e não gerar evidência de acesso a dado
# ------------------------------------------------------------------
bold "2. Requisição sem token"

SEM_TOKEN_TRACE=$(openssl rand -hex 16)
SEM_TOKEN_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
  -H "Content-Type: application/json" \
  -H "traceparent: 00-${SEM_TOKEN_TRACE}-$(openssl rand -hex 8)-01" \
  -d "{\"cpf\":\"${CPF}\"}" \
  "$APP_HOST/api/v1/cidadaos/consulta")
if [ "$SEM_TOKEN_STATUS" = "401" ]; then
    ok "sem token: HTTP 401 (o dado não é entregue)"
else
    fail "sem token: HTTP $SEM_TOKEN_STATUS (esperado 401)"
fi

# Um X-User-ID livre não identifica ninguém: com token válido, vale o operador do token.
FORJADO="forjado_$(openssl rand -hex 3)"
curl -s -o /dev/null -X POST \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-User-ID: $FORJADO" \
  -d "{\"cpf\":\"${CPF}\"}" \
  "$APP_HOST/api/v1/cidadaos/consulta" || true
echo

# ------------------------------------------------------------------
# Passo 3 - Evidência no PostgreSQL
# ------------------------------------------------------------------
bold "3. Evidência no PostgreSQL"

# Pequena espera: a gravação é síncrona com a resposta, mas a margem evita
# instabilidade em máquinas mais lentas.
sleep 1

QUERY="SELECT seq, trace_id, span_id, operator_id, action, resource, COALESCE(subject_ref, ''), payload_hash, event_timestamp, prev_hash, record_hash
       FROM audit_log
       WHERE trace_id = '${TRACE_ID}'
       ORDER BY seq DESC
       LIMIT 1;"

DB_ROW=$($DOCKER exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A -F '|' -c "$QUERY" || true)

DB_PAYLOAD_HASH=""
if [ -z "$DB_ROW" ]; then
    fail "Nenhum registro encontrado em audit_log para trace_id=$TRACE_ID"
    info "Verifique se os containers estão de pé (docker ps) e se o Quarkus está rodando."
else
    IFS='|' read -r DB_SEQ DB_TRACE_ID DB_SPAN_ID DB_OPERATOR DB_ACTION DB_RESOURCE DB_SUBJECT_REF DB_PAYLOAD_HASH DB_TIMESTAMP DB_PREV_HASH DB_RECORD_HASH <<< "$DB_ROW"
    ok "Registro encontrado"
    info "seq:          $DB_SEQ  (posição na cadeia de hash)"
    info "trace_id:     $DB_TRACE_ID"
    info "span_id:      $DB_SPAN_ID"
    info "operator_id:  $DB_OPERATOR  (principal do token JWT validado)"
    info "action:       $DB_ACTION"
    info "resource:     $DB_RESOURCE"
    info "subject_ref:  ${DB_SUBJECT_REF:-<vazio>}"
    info "payload_hash: $DB_PAYLOAD_HASH"
    info "timestamp:    $DB_TIMESTAMP"
    info "prev_hash:    $DB_PREV_HASH"
    info "record_hash:  $DB_RECORD_HASH"
    echo

    [ "$DB_TRACE_ID" = "$TRACE_ID" ] \
        && ok "trace_id do banco bate com o traceparent enviado" \
        || fail "trace_id do banco NÃO bate com o traceparent enviado"

    [ "$DB_OPERATOR" = "$OPERATOR" ] \
        && ok "operator_id é o do token ($OPERATOR)" \
        || fail "operator_id ($DB_OPERATOR) difere do operador do token ($OPERATOR)"

    case "$DB_ROW" in
        *"$CPF"*) fail "o CPF aparece em claro na linha gravada no banco" ;;
        *)        ok "o CPF não aparece em claro na linha gravada no banco" ;;
    esac

    if [ -x "$SCRIPT_DIR/scripts/subject-ref.sh" ] && [ -n "$DB_SUBJECT_REF" ]; then
        EXPECTED_REF=$("$SCRIPT_DIR/scripts/subject-ref.sh" "$CPF" 2>/dev/null || true)
        if [ "$EXPECTED_REF" = "$DB_SUBJECT_REF" ]; then
            ok "subject_ref confere com o HMAC do CPF consultado"
        else
            info "subject_ref difere do calculado aqui (a aplicação usa outra chave? defina AUDITGOV_HMAC_KEY)"
        fi
    fi
fi

# Contagens dos dois passos anteriores (sem expor dado pessoal).
SEM_TOKEN_ROWS=$($DOCKER exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A \
    -c "SELECT count(*) FROM audit_log WHERE trace_id = '${SEM_TOKEN_TRACE}';" 2>/dev/null || echo "?")
if [ "$SEM_TOKEN_ROWS" = "0" ]; then
    ok "a requisição recusada (401) não gerou evidência de acesso a dado"
else
    fail "a requisição recusada gerou $SEM_TOKEN_ROWS registro(s) (esperado 0)"
fi
FORJADO_ROWS=$($DOCKER exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -A \
    -c "SELECT count(*) FROM audit_log WHERE operator_id = '${FORJADO}';" 2>/dev/null || echo "?")
if [ "$FORJADO_ROWS" = "0" ]; then
    ok "o cabeçalho X-User-ID foi ignorado (nenhum registro com o operador '$FORJADO')"
else
    fail "há $FORJADO_ROWS registro(s) com o operador declarado em X-User-ID ($FORJADO)"
fi
echo

# ------------------------------------------------------------------
# Passo 4 - Jaeger
# ------------------------------------------------------------------
bold "4. Jaeger"
info "Abra no navegador:"
info "$JAEGER_UI/trace/$TRACE_ID"
info ""
info "No trace, expanda o span 'auditgov.evidence' e confira a tag audit.payload.hmac."
info "Ela deve ser idêntica ao payload_hash impresso acima:"
info ""
echo "  ${DB_PAYLOAD_HASH:-<sem registro>}"
echo

TRACE_JSON=""
for _ in 1 2 3 4 5; do
    TRACE_JSON=$(curl -s "$JAEGER_UI/api/traces/$TRACE_ID" || true)
    echo "$TRACE_JSON" | grep -q '"traceID"' && break
    sleep 1
done
if echo "$TRACE_JSON" | grep -q '"traceID"'; then
    ok "trace encontrado no Jaeger"
    if echo "$TRACE_JSON" | grep -q "$CPF"; then
        fail "o CPF aparece no rastro do Jaeger"
    else
        ok "o CPF não aparece no rastro do Jaeger"
    fi
else
    info "Não foi possível ler o trace pela API do Jaeger ($JAEGER_UI/api/traces/$TRACE_ID)."
    info "Confira manualmente se o CPF não aparece em nenhuma tag do trace."
fi
echo

# ------------------------------------------------------------------
# Passo 5 - Verificação da cadeia de hash (endpoint do auditor)
# ------------------------------------------------------------------
bold "5. Verificação da cadeia de hash"
AUDITOR_TOKEN=$("$SCRIPT_DIR/scripts/mint-token.sh" "auditor_teste" auditor)

SEM_PAPEL_STATUS=$(curl -s -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $TOKEN" "$APP_HOST/api/v1/auditoria/cadeia/verificacao")
if [ "$SEM_PAPEL_STATUS" = "403" ]; then
    ok "token sem o papel 'auditor': HTTP 403 (a verificação é só do auditor)"
else
    fail "token sem o papel 'auditor': HTTP $SEM_PAPEL_STATUS (esperado 403)"
fi

CADEIA_FILE=$(mktemp)
CADEIA_STATUS=$(curl -s -o "$CADEIA_FILE" -w '%{http_code}' \
  -H "Authorization: Bearer $AUDITOR_TOKEN" "$APP_HOST/api/v1/auditoria/cadeia/verificacao")
if [ "$CADEIA_STATUS" = "200" ]; then
    if grep -q '"valida":true' "$CADEIA_FILE"; then
        ok "cadeia íntegra (HTTP 200, valida=true)"
    else
        fail "a cadeia NÃO está íntegra (valida=false)"
    fi
    if [ "$HAS_JQ" = "1" ]; then
        jq . "$CADEIA_FILE" 2>/dev/null | sed 's/^/  /' || sed 's/^/  /' "$CADEIA_FILE"
    else
        sed 's/^/  /' "$CADEIA_FILE"
    fi
    echo
    info "A verificação INDEPENDENTE, fora da aplicação (psql + openssl), é:"
    info "  AUDITGOV_HMAC_KEY='<chave>' ./scripts/verify-chain.sh"
    info "Para detectar a remoção das ÚLTIMAS linhas, guarde fora do banco o par (ultimoSeq, ultimoHash) acima"
    info "e passe-o depois em --ancora-seq / --ancora-hash (ou em ?ancoraSeq=&ancoraHash= no endpoint)."
else
    fail "verificação da cadeia: HTTP $CADEIA_STATUS (esperado 200)"
fi
rm -f "$CADEIA_FILE"
echo

# ------------------------------------------------------------------
# Passo 6 - Proteção contra alteração (gatilho append-only)
# ------------------------------------------------------------------
bold "6. Proteção contra alteração no banco"
if [ -n "$DB_ROW" ]; then
    # UPDATE sem efeito (mantém o mesmo valor): se o gatilho não existir, nada é alterado.
    UPDATE_OUT=$($DOCKER exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" \
        -c "UPDATE audit_log SET operator_id = operator_id WHERE trace_id = '${TRACE_ID}';" 2>&1 || true)
    if echo "$UPDATE_OUT" | grep -q "append-only"; then
        ok "o banco rejeitou o UPDATE sobre a evidência"
        info "$(echo "$UPDATE_OUT" | head -1)"
    else
        fail "o UPDATE não foi rejeitado: a proteção append-only não está ativa"
        info "$(echo "$UPDATE_OUT" | head -1)"
    fi
else
    info "Sem registro para testar."
fi
echo

rm -f "$HTTP_BODY_FILE"

bold "Resumo"
info "TraceID usado nesta execução: $TRACE_ID"
info "Rode de novo com outro CPF: ./test-audit.sh 98765432100"
info "Caso de falha (auditoria de tentativa): ./test-audit.sh 00000000000"
info "Dois serviços, um trace só: ./scripts/e2e-trace.sh (veja facade/README.md)"
