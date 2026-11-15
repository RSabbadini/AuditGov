#!/usr/bin/env bash
#
# verify-chain.sh - verificacao INDEPENDENTE da cadeia de hash de audit_log, fora da aplicacao.
#
# Le audit_log direto do PostgreSQL e recalcula, linha a linha, o HMAC-SHA-256 do registro com a
# subchave da cadeia, usando so psql e openssl. Confere, nesta ordem (a mesma do verificador Java):
#   1. seq continua: 1, 2, 3, ... sem buracos;
#   2. prev_hash de cada linha = record_hash da anterior (a primeira usa 64 zeros);
#   3. record_hash = HMAC recalculado sobre o conteudo da linha.
# Opcionalmente confere uma ANCORA externa: o par (seq, hash) da cabeca da cadeia guardado fora do
# banco em um momento anterior.
#
# O que isto prova e o que NAO prova:
#   - Detecta alteracao, remocao, reordenacao ou insercao de linhas feitas SEM a chave. Nao as impede.
#   - Quem tem a chave E acesso de escrita ao banco pode refazer a cadeia inteira; a ancora externa
#     expoe isso (ANCHOR_MISMATCH), desde que tenha sido guardada antes e fora do banco.
#   - A remocao das ULTIMAS linhas deixa uma cadeia mais curta e valida: so a ancora a revela
#     (TAIL_TRUNCATED).
#   - O resultado diz se a cadeia esta integra, nao QUEM a alterou.
#
# Uso:
#   AUDITGOV_HMAC_KEY='<chave>' ./scripts/verify-chain.sh
#   AUDITGOV_HMAC_KEY='<chave>' ./scripts/verify-chain.sh --ancora-seq 1500 --ancora-hash <64 hex>
#   (sem AUDITGOV_HMAC_KEY usa a chave de desenvolvimento do application.properties)
#
# Variaveis opcionais: DOCKER_CMD (padrao "sudo docker"; use "docker" se seu usuario esta no grupo
# docker), PG_CONTAINER, PG_USER, PG_DB.
#
# E lento (um processo openssl por linha): serve para conferir a cadeia de forma independente em tabelas
# pequenas ou medias. Para tabelas grandes use o endpoint GET /api/v1/auditoria/cadeia/verificacao.
# Requer PostgreSQL 14 ou superior (extract(epoch ...) devolvendo numeric) e OpenSSL 1.1 ou superior.
#
# Formato canonico v1 (deve ser identico ao de AuditChain.canonical): campos separados por LF, sem LF
# final: "auditgov-chain-v1", seq, id, trace_id, span_id, operator_id, action, resource, subject_ref
# (vazio se nulo), payload_hash, event_timestamp em MICROSSEGUNDOS desde 1970-01-01 UTC, prev_hash.
set -euo pipefail

KEY="${AUDITGOV_HMAC_KEY:-auditgov-dev-key-nao-usar-em-producao}"
DOCKER="${DOCKER_CMD:-sudo docker}"
PG_CONTAINER="${PG_CONTAINER:-auditgov-postgres}"
PG_USER="${PG_USER:-audit_user}"
PG_DB="${PG_DB:-auditgov_db}"

ANCORA_SEQ=""
ANCORA_HASH=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ancora-seq)  ANCORA_SEQ="${2:?--ancora-seq exige um numero}"; shift 2 ;;
    --ancora-hash) ANCORA_HASH="${2:?--ancora-hash exige 64 hexadecimais}"; shift 2 ;;
    -h|--help)     sed -n '2,36p' "$0"; exit 0 ;;
    *)             echo "Argumento desconhecido: $1 (use --help)" >&2; exit 2 ;;
  esac
done
if [ -n "$ANCORA_SEQ" ] || [ -n "$ANCORA_HASH" ]; then
  [ -n "$ANCORA_SEQ" ] && [ -n "$ANCORA_HASH" ] || { echo "A ancora exige --ancora-seq e --ancora-hash juntos." >&2; exit 2; }
  [[ "$ANCORA_SEQ" =~ ^[1-9][0-9]*$ ]] || { echo "--ancora-seq deve ser um inteiro >= 1." >&2; exit 2; }
  [[ "$ANCORA_HASH" =~ ^[0-9a-f]{64}$ ]] || { echo "--ancora-hash deve ter 64 hexadecimais minusculos." >&2; exit 2; }
fi

command -v openssl >/dev/null || { echo "openssl nao encontrado." >&2; exit 2; }

CHAVE_HEX="$(printf '%s' 'auditgov/audit-chain/v1' \
  | openssl dgst -sha256 -mac HMAC -macopt "key:${KEY}" -binary \
  | od -An -tx1 | tr -d ' \n')"

QUERY="SELECT seq, id, trace_id, span_id, operator_id, action, resource, COALESCE(subject_ref, ''),
              payload_hash, (extract(epoch FROM event_timestamp) * 1000000)::bigint, prev_hash, record_hash
       FROM audit_log ORDER BY seq"

LINHAS="$(mktemp)"
ERROS="$(mktemp)"
trap 'rm -f "$LINHAS" "$ERROS"' EXIT

# O separador de campos e o caractere de controle 0x1f (US), que nao ocorre nos campos gravados.
# client_encoding=UTF8 e obrigatorio: o hash e calculado sobre os bytes UTF-8 dos campos, e um psql com
# outra codificacao trocaria acentos do operador por '?', o que reprovaria uma cadeia valida.
# shellcheck disable=SC2086
if ! $DOCKER exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "dbname=${PG_DB} client_encoding=UTF8" -At -F $'\x1f' -c "$QUERY" > "$LINHAS" 2> "$ERROS"; then
  echo "Nao consegui ler audit_log: $(head -1 "$ERROS")" >&2
  exit 2
fi

falhar() {   # $1 = seq, $2 = motivo, $3 = detalhe
  echo "RESULTADO: FALHOU  seq=$1  motivo=$2"
  echo "           $3"
  echo "           linhas conferidas antes da falha: $CONTADOR"
  exit 1
}

ESPERADO_SEQ=1
PREV_ESPERADO="$(printf '%064d' 0)"
CONTADOR=0
ULTIMO_SEQ=""
ULTIMO_HASH=""
HASH_NA_ANCORA=""

while IFS=$'\x1f' read -r SEQ ID TRACE SPAN OPERADOR ACAO RECURSO SUJEITO PAYLOAD MICROS PREV REC; do
  [ -n "$SEQ" ] || continue

  if [ "$SEQ" != "$ESPERADO_SEQ" ]; then
    falhar "$SEQ" "SEQ_GAP" "esperava seq=$ESPERADO_SEQ e encontrou seq=$SEQ (registro removido, inserido fora de ordem ou duplicado)"
  fi
  if [ "$PREV" != "$PREV_ESPERADO" ]; then
    falhar "$SEQ" "PREV_HASH_MISMATCH" "prev_hash nao e o record_hash da linha anterior (ou o genesis, na primeira linha)"
  fi

  CALCULADO="$(printf 'auditgov-chain-v1\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n%s' \
      "$SEQ" "$ID" "$TRACE" "$SPAN" "$OPERADOR" "$ACAO" "$RECURSO" "$SUJEITO" "$PAYLOAD" "$MICROS" "$PREV" \
    | openssl dgst -sha256 -mac HMAC -macopt "hexkey:${CHAVE_HEX}" | awk '{print $NF}')"
  if [ "$CALCULADO" != "$REC" ]; then
    falhar "$SEQ" "RECORD_HASH_MISMATCH" "o HMAC recalculado nao confere com record_hash (conteudo alterado, ou chave diferente da usada na gravacao)"
  fi

  if [ -n "$ANCORA_SEQ" ] && [ "$SEQ" = "$ANCORA_SEQ" ]; then HASH_NA_ANCORA="$REC"; fi
  PREV_ESPERADO="$REC"
  ESPERADO_SEQ=$((SEQ + 1))
  ULTIMO_SEQ="$SEQ"
  ULTIMO_HASH="$REC"
  CONTADOR=$((CONTADOR + 1))
done < "$LINHAS"

if [ -n "$ANCORA_SEQ" ]; then
  if [ "${ULTIMO_SEQ:-0}" -lt "$ANCORA_SEQ" ]; then
    falhar "${ULTIMO_SEQ:-0}" "TAIL_TRUNCATED" "a cadeia termina em seq=${ULTIMO_SEQ:-0}, antes da ancora (seq=$ANCORA_SEQ): registros finais removidos"
  fi
  if [ "$HASH_NA_ANCORA" != "$ANCORA_HASH" ]; then
    falhar "$ANCORA_SEQ" "ANCHOR_MISMATCH" "o hash gravado em seq=$ANCORA_SEQ difere da ancora guardada fora do banco"
  fi
fi

if [ "$CONTADOR" -eq 0 ]; then
  echo "RESULTADO: OK  cadeia vazia (0 registros); nada a verificar."
  exit 0
fi

echo "RESULTADO: OK  $CONTADOR registro(s) verificado(s)."
echo "           ultimo seq : $ULTIMO_SEQ"
echo "           ultimo hash: $ULTIMO_HASH"
if [ -n "$ANCORA_SEQ" ]; then
  echo "           ancora (seq=$ANCORA_SEQ) confere."
else
  echo "           Guarde (seq, hash) fora do banco: so uma ancora externa detecta a remocao das ultimas linhas."
fi
exit 0
