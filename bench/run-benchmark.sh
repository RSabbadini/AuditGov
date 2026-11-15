#!/usr/bin/env bash
#
# run-benchmark.sh - executa a matriz de medicao para UM modo de execucao da aplicacao.
#
# A aplicacao precisa estar rodando no modo que se quer medir (veja bench/README.md). O script:
#   1. emite um token JWT de laboratorio por usuario virtual (operadores bench_1 ... bench_N) e um
#      token com papel "auditor" (scripts/mint-token.sh);
#   2. confirma que a aplicacao responde 200 com esses tokens;
#   3. verifica a cadeia de hash ANTES da carga (estado inicial);
#   4. faz um aquecimento e, para cada nivel de carga (10 e 100 usuarios virtuais), repete a medicao
#      3 vezes, gravando uma linha por repeticao em bench/results/<rotulo>.csv;
#   5. verifica a cadeia DEPOIS e compara quantas evidencias novas foram gravadas com quantas
#      requisicoes o k6 enviou (em modo com auditoria, sem perda de evidencia, as duas cifras coincidem
#      quando todas as respostas foram 200).
#
# Uso:   ./bench/run-benchmark.sh <rotulo>
# Ex.:   ./bench/run-benchmark.sh sem_auditoria
#        ./bench/run-benchmark.sh com_auditoria_always_on
#        ./bench/run-benchmark.sh com_auditoria_amostragem_10pct
#
# Variaveis opcionais: BASE_URL, DURATION (padrao 60s), WARMUP (padrao 20s), VUS_LIST (padrao "10 100"),
# REPS (padrao 3), TOKEN_VALIDADE (padrao 14400 s), PG_CONTAINER (auditgov-postgres) e DOCKER_CMD
# (padrao "docker"; os dois ultimos servem so para registrar a versao do PostgreSQL).
#
# Todos os arquivos ficam em bench/results/ (ignorado pelo git). Os tokens sao credenciais de
# LABORATORIO (emissor de desenvolvimento) e ficam em bench/results/<rotulo>_tokens.txt, com
# permissao restrita; apague-os depois da medicao.
set -euo pipefail

LABEL="${1:?Informe um rotulo para este modo, por exemplo: sem_auditoria}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
DURATION="${DURATION:-60s}"
WARMUP="${WARMUP:-20s}"
VUS_LIST="${VUS_LIST:-10 100}"
REPS="${REPS:-3}"
TOKEN_VALIDADE="${TOKEN_VALIDADE:-14400}"
PG_CONTAINER="${PG_CONTAINER:-auditgov-postgres}"
DOCKER="${DOCKER_CMD:-docker}"

for CMD in k6 jq curl openssl; do
  command -v "$CMD" >/dev/null || { echo "$CMD nao encontrado. Instale-o (k6: https://k6.io/docs/get-started/installation/)."; exit 1; }
done
[[ "$LABEL" =~ ^[A-Za-z0-9_.-]+$ ]] || { echo "Rotulo invalido: use letras, digitos e . _ -"; exit 1; }

cd "$(dirname "$0")/.."
mkdir -p bench/results
CSV="bench/results/${LABEL}.csv"
CADEIA_CSV="bench/results/${LABEL}_cadeia.csv"
AMBIENTE="bench/results/${LABEL}_ambiente.txt"
TOKENS_FILE="$PWD/bench/results/${LABEL}_tokens.txt"

# ---------------------------------------------------------------- ambiente (melhor esforco)
{
  echo "rotulo: $LABEL"
  echo "data (UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "so: $(uname -sr)"
  echo "cpu: $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//' || true)"
  echo "nucleos: $(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo '?')"
  echo "memoria: $(grep -m1 MemTotal /proc/meminfo 2>/dev/null | sed 's/^MemTotal: *//' || true)"
  echo "java (do PATH desta sessao): $(java -version 2>&1 | grep -v 'Picked up' | head -1 || true)"
  echo "k6: $(k6 version 2>&1 | head -1 || true)"
  echo "postgresql: $($DOCKER exec "$PG_CONTAINER" psql -U audit_user -d auditgov_db -At -c 'select version()' 2>/dev/null || echo 'nao consultado')"
  echo "parametros: DURATION=$DURATION WARMUP=$WARMUP VUS_LIST=\"$VUS_LIST\" REPS=$REPS BASE_URL=$BASE_URL"
} > "$AMBIENTE" 2>/dev/null || true

# ---------------------------------------------------------------- tokens
MAX_VUS=0
for V in $VUS_LIST; do
  [[ "$V" =~ ^[0-9]+$ ]] || { echo "VUS_LIST invalido: $VUS_LIST"; exit 1; }
  [ "$V" -gt "$MAX_VUS" ] && MAX_VUS="$V"
done
[ "$MAX_VUS" -ge 10 ] || MAX_VUS=10   # o aquecimento usa 10 usuarios virtuais

echo ">> Emitindo $MAX_VUS tokens de laboratorio (operadores bench_1 ... bench_${MAX_VUS}) e 1 token de auditor"
( umask 077; : > "$TOKENS_FILE" )
for N in $(seq 1 "$MAX_VUS"); do
  ./scripts/mint-token.sh "bench_${N}" operador "$TOKEN_VALIDADE" >> "$TOKENS_FILE"
done
TOKEN_AUDITOR="$(./scripts/mint-token.sh bench_auditor auditor "$TOKEN_VALIDADE")"

# ---------------------------------------------------------------- a aplicacao responde?
PRIMEIRO_TOKEN="$(head -n 1 "$TOKENS_FILE")"
STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${BASE_URL}/api/v1/cidadaos/consulta" \
  -H "Authorization: Bearer ${PRIMEIRO_TOKEN}" -H 'Content-Type: application/json' \
  -d '{"cpf":"12345678900"}' || true)"
if [ "$STATUS" != "200" ]; then
  echo "A aplicacao respondeu HTTP ${STATUS:-000} (esperado 200) em ${BASE_URL}."
  echo "Confira se ela esta no ar e se confia no emissor de laboratorio (mp.jwt.verify.* em application.properties)."
  exit 1
fi

# ---------------------------------------------------------------- verificacao da cadeia
# Preenche V_STATUS, V_VALIDA, V_REGISTROS, V_SEGUNDOS e V_DETALHE.
verificar_cadeia() {
  local saida corpo ultima
  saida="$(curl -s -m 900 -w '\n%{http_code} %{time_total}' \
    -H "Authorization: Bearer ${TOKEN_AUDITOR}" \
    "${BASE_URL}/api/v1/auditoria/cadeia/verificacao" || true)"
  ultima="$(printf '%s\n' "$saida" | tail -n 1)"
  corpo="$(printf '%s\n' "$saida" | sed '$d')"
  V_STATUS="${ultima%% *}"
  V_SEGUNDOS="${ultima##* }"
  V_DETALHE=""
  if [ "$V_STATUS" = "200" ]; then
    V_VALIDA="$(printf '%s' "$corpo" | jq -r '.valida')"
    V_REGISTROS="$(printf '%s' "$corpo" | jq -r '.registrosVerificados')"
    if [ "$V_VALIDA" != "true" ]; then
      V_DETALHE="$(printf '%s' "$corpo" | jq -r '"seq=\(.seqComFalha) motivo=\(.motivo) \(.detalhe)"')"
    fi
  else
    V_VALIDA="indisponivel"
    V_REGISTROS="-1"
    V_SEGUNDOS="${V_SEGUNDOS:-0}"
  fi
}

echo "rotulo,momento,http,valida,registros,segundos_verificacao" > "$CADEIA_CSV"
registrar_cadeia() {   # $1 = antes | depois
  echo "${LABEL},$1,${V_STATUS:-000},${V_VALIDA},${V_REGISTROS},${V_SEGUNDOS:-0}" >> "$CADEIA_CSV"
  echo "   cadeia ($1): valida=${V_VALIDA} registros=${V_REGISTROS} verificacao em ${V_SEGUNDOS:-0} s (HTTP ${V_STATUS:-000})"
  if [ -n "$V_DETALHE" ]; then
    echo "   ATENCAO: a cadeia NAO esta integra: $V_DETALHE"
  fi
}

echo ">> Verificacao da cadeia antes da carga"
verificar_cadeia
registrar_cadeia antes
REGISTROS_ANTES="$V_REGISTROS"

# ---------------------------------------------------------------- carga
echo "rotulo,vus,repeticao,p50_ms,p95_ms,p99_ms,rps,erro_pct,reqs" > "$CSV"
TOTAL_REQS=0

echo ">> Aquecimento (${WARMUP}, 10 usuarios virtuais)"
k6 run --quiet -e BASE_URL="$BASE_URL" -e TOKENS_FILE="$TOKENS_FILE" -e VUS=10 -e DURATION="$WARMUP" \
  -e OUT=bench/results/aquecimento.json bench/auditgov-load.js > /dev/null
TOTAL_REQS=$((TOTAL_REQS + $(jq -r '.reqs | floor' bench/results/aquecimento.json)))

for VUS in $VUS_LIST; do
  for REP in $(seq 1 "$REPS"); do
    OUT="bench/results/${LABEL}_vus${VUS}_rep${REP}.json"
    echo ">> ${LABEL}: ${VUS} usuarios virtuais, repeticao ${REP}/${REPS} (${DURATION})"
    k6 run --quiet -e BASE_URL="$BASE_URL" -e TOKENS_FILE="$TOKENS_FILE" -e VUS="$VUS" -e DURATION="$DURATION" \
      -e OUT="$OUT" bench/auditgov-load.js > /dev/null
    jq -r --arg l "$LABEL" --arg v "$VUS" --arg r "$REP" \
      '[$l, $v, $r, .p50_ms, .p95_ms, .p99_ms, .rps, .erro_pct, (.reqs | floor)] | @csv' "$OUT" >> "$CSV"
    TOTAL_REQS=$((TOTAL_REQS + $(jq -r '.reqs | floor' "$OUT")))
  done
done

# ---------------------------------------------------------------- cadeia depois
echo
echo ">> Verificacao da cadeia depois da carga"
verificar_cadeia
registrar_cadeia depois

if [ "$REGISTROS_ANTES" -ge 0 ] && [ "$V_REGISTROS" -ge 0 ]; then
  NOVAS=$((V_REGISTROS - REGISTROS_ANTES))
  echo "   evidencias novas na cadeia: ${NOVAS}; requisicoes enviadas pelo k6 (aquecimento + medicao): ${TOTAL_REQS}"
  echo "   Em modo com auditoria, as duas cifras devem coincidir quando todas as respostas foram 200."
  echo "   Se evidencias < requisicoes, houve resposta recusada (veja erro_pct) ou perda de evidencia"
  echo "   (modo lenient). Investigue antes de usar os numeros. Em sem_auditoria, evidencias novas = 0."
else
  echo "   Nao foi possivel contar as evidencias (endpoint de verificacao indisponivel)."
fi

echo
echo "Resultados em $CSV:"
column -s, -t "$CSV" 2>/dev/null || cat "$CSV"
echo
echo "Cadeia (antes/depois) em $CADEIA_CSV; ambiente em $AMBIENTE."
echo "Os tokens de laboratorio estao em bench/results/${LABEL}_tokens.txt; apague-os quando terminar."

# Uma cadeia que nao esta integra depois da carga e um achado: o codigo de saida 3 impede que passe despercebido.
if [ "${V_VALIDA}" = "false" ]; then
  echo "ATENCAO: a cadeia de hash NAO estava integra ao final da medicao (${V_DETALHE}). Nao use estes numeros sem entender o motivo."
  exit 3
fi
