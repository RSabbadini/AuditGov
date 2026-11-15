#!/usr/bin/env bash
#
# subject-ref.sh - calcula o subject_ref (HMAC-SHA-256) de um CPF.
#
# Serve para responder "quem acessou os dados deste titular?" (LGPD, Art. 18) sem que o CPF
# esteja gravado no banco: calcula-se a referencia do CPF com a mesma chave da aplicacao e
# consulta-se a trilha por ela.
#
# Uso:
#   AUDITGOV_HMAC_KEY='<chave>' ./scripts/subject-ref.sh 12345678900
#   (sem AUDITGOV_HMAC_KEY usa a chave de desenvolvimento do application.properties)
#
# Exemplo de consulta:
#   REF=$(./scripts/subject-ref.sh 12345678900)
#   docker exec -it auditgov-postgres psql -U audit_user -d auditgov_db \
#     -c "SELECT operator_id, action, resource, event_timestamp FROM audit_log WHERE subject_ref = '$REF' ORDER BY event_timestamp DESC;"
#
# Deve produzir o mesmo valor que AuditCrypto.subjectRef(): subchave = HMAC(chave, "auditgov/subject-ref/v1"),
# referencia = HMAC(subchave, digitos do CPF).
set -euo pipefail

KEY="${AUDITGOV_HMAC_KEY:-auditgov-dev-key-nao-usar-em-producao}"
CPF_DIGITS="$(printf '%s' "${1:?Informe o CPF como primeiro argumento}" | tr -cd '0-9')"
[ -n "$CPF_DIGITS" ] || { echo "O CPF informado nao tem digitos." >&2; exit 1; }

command -v openssl >/dev/null || { echo "openssl nao encontrado." >&2; exit 1; }

SUBKEY_HEX="$(printf '%s' 'auditgov/subject-ref/v1' \
  | openssl dgst -sha256 -mac HMAC -macopt "key:${KEY}" -binary \
  | od -An -tx1 | tr -d ' \n')"

printf '%s' "$CPF_DIGITS" \
  | openssl dgst -sha256 -mac HMAC -macopt "hexkey:${SUBKEY_HEX}" \
  | awk '{print $NF}'
