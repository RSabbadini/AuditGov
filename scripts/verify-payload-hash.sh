#!/usr/bin/env bash
#
# verify-payload-hash.sh - recalcula o payload_hash (HMAC-SHA-256) de um conteudo.
#
# Le o conteudo da entrada padrao e imprime o HMAC. Quem tem a chave pode conferir se o conteudo
# entregue a um operador corresponde ao payload_hash gravado na trilha.
#
# Uso:
#   curl -s -X POST -H 'Content-Type: application/json' -d '{"cpf":"12345678900"}' \
#        http://localhost:8080/api/v1/cidadaos/consulta | AUDITGOV_HMAC_KEY='<chave>' ./scripts/verify-payload-hash.sh
#
# Atencao: o HMAC e calculado sobre os BYTES exatos do JSON serializado pela aplicacao. A conferencia
# externa so coincide se esses bytes forem identicos (sem quebra de linha ou espacos extras). O
# argumento principal de integridade continua sendo o batimento Jaeger x PostgreSQL.
#
# Deve produzir o mesmo valor que AuditCrypto.payloadHash(): subchave = HMAC(chave, "auditgov/payload-hash/v1").
set -euo pipefail

KEY="${AUDITGOV_HMAC_KEY:-auditgov-dev-key-nao-usar-em-producao}"
command -v openssl >/dev/null || { echo "openssl nao encontrado." >&2; exit 1; }

SUBKEY_HEX="$(printf '%s' 'auditgov/payload-hash/v1' \
  | openssl dgst -sha256 -mac HMAC -macopt "key:${KEY}" -binary \
  | od -An -tx1 | tr -d ' \n')"

openssl dgst -sha256 -mac HMAC -macopt "hexkey:${SUBKEY_HEX}" | awk '{print $NF}'
