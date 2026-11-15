#!/usr/bin/env bash
#
# export-openapi.sh - salva o contrato OpenAPI REAL gerado pela aplicacao (Anexo A do relatorio).
#
# Com a aplicacao em execucao (./mvnw quarkus:dev), grava docs/openapi.yaml e docs/openapi.json a
# partir de /q/openapi. Esses arquivos sao a saida da extensao quarkus-smallrye-openapi sobre as
# anotacoes do codigo, nao um texto escrito a mao.
#
# Alternativa sem subir a aplicacao: ./mvnw package -DskipTests gera os mesmos arquivos em
# target/openapi/ (propriedade quarkus.smallrye-openapi.store-schema-directory).
#
# A captura de tela do Swagger UI (http://localhost:8080/q/swagger-ui) deve ser feita manualmente.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
cd "$(dirname "$0")/.."
mkdir -p docs

curl -fsS "${BASE_URL}/q/openapi" -o docs/openapi.yaml
curl -fsS "${BASE_URL}/q/openapi?format=json" -o docs/openapi.json

echo "Gravados: docs/openapi.yaml ($(wc -l < docs/openapi.yaml) linhas) e docs/openapi.json"
