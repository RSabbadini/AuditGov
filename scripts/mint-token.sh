#!/usr/bin/env bash
#
# mint-token.sh - emite um token JWT (RS256) de DESENVOLVIMENTO para chamar a API.
#
# Em producao quem emite o token e o provedor de identidade (IdP); a aplicacao so o VALIDA.
# Este script faz o papel do IdP no laboratorio, assinando com o par de chaves de teste
# (src/test/resources/jwt/auditgov-dev-private.pem), cuja chave publica a aplicacao ja confia
# (src/main/resources/publicKey.pem). NAO use este par de chaves fora do laboratorio.
#
# Uso:
#   ./scripts/mint-token.sh <operador> [papeis] [validade_em_segundos]
#   ./scripts/mint-token.sh fiscal_maria                  # papel "operador", 1 hora
#   ./scripts/mint-token.sh auditora_ana auditor 7200     # papel "auditor", 2 horas
#   ./scripts/mint-token.sh ana operador,auditor          # varios papeis, separados por virgula
#
# O token vai para a saida padrao (e so ele), para uso em substituicao de comando:
#   TOKEN=$(./scripts/mint-token.sh fiscal_maria)
#   curl -X POST http://localhost:8080/api/v1/cidadaos/consulta \
#     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"cpf":"12345678900"}'
#
# Variaveis opcionais: AUDITGOV_JWT_PRIVATE_KEY (caminho da chave privada PEM, PKCS#8) e
# AUDITGOV_JWT_ISSUER (padrao: o emissor configurado em application.properties).
#
# O operador gravado na trilha e a claim "upn" (a aplicacao usa, nesta ordem, upn,
# preferred_username e sub). Aceita apenas letras, digitos e . _ @ - no nome, para que o
# JSON do token nao precise de escape.
set -euo pipefail

OPERADOR="${1:?Informe o operador, por exemplo: fiscal_maria}"
PAPEIS="${2:-operador}"
VALIDADE="${3:-3600}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHAVE="${AUDITGOV_JWT_PRIVATE_KEY:-$SCRIPT_DIR/../src/test/resources/jwt/auditgov-dev-private.pem}"
EMISSOR="${AUDITGOV_JWT_ISSUER:-https://auditgov.local/dev-issuer}"

command -v openssl >/dev/null || { echo "openssl nao encontrado." >&2; exit 1; }
[ -r "$CHAVE" ] || { echo "Chave privada nao encontrada: $CHAVE" >&2; exit 1; }
[[ "$OPERADOR" =~ ^[A-Za-z0-9._@-]+$ ]] || { echo "Operador invalido: use apenas letras, digitos e . _ @ -" >&2; exit 1; }
[[ "$PAPEIS" =~ ^[A-Za-z0-9._,-]+$ ]] || { echo "Papeis invalidos: use apenas letras, digitos e . _ - separados por virgula" >&2; exit 1; }
[[ "$VALIDADE" =~ ^[0-9]+$ ]] || { echo "Validade invalida: informe segundos (inteiro)." >&2; exit 1; }

base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

AGORA="$(date +%s)"
EXPIRA=$((AGORA + VALIDADE))
JTI="$(openssl rand -hex 16)"

PAPEIS_JSON=""
IFS=',' read -r -a LISTA <<< "$PAPEIS"
for PAPEL in "${LISTA[@]}"; do
  [ -n "$PAPEL" ] || continue
  PAPEIS_JSON+="${PAPEIS_JSON:+,}\"${PAPEL}\""
done

CABECALHO='{"alg":"RS256","typ":"JWT"}'
CORPO="$(printf '{"iss":"%s","sub":"%s","upn":"%s","groups":[%s],"iat":%d,"exp":%d,"jti":"%s"}' \
  "$EMISSOR" "$OPERADOR" "$OPERADOR" "$PAPEIS_JSON" "$AGORA" "$EXPIRA" "$JTI")"

ENTRADA="$(printf '%s' "$CABECALHO" | base64url).$(printf '%s' "$CORPO" | base64url)"
ASSINATURA="$(printf '%s' "$ENTRADA" | openssl dgst -sha256 -sign "$CHAVE" -binary | base64url)"

printf '%s.%s\n' "$ENTRADA" "$ASSINATURA"
