# AuditGov: Roteiro de teste e validação da PoC

## 0. Pré-requisitos

```bash
# Esta versão mudou o esquema de audit_log (colunas seq, prev_hash e record_hash). Se você já tinha o
# banco de uma versão anterior, recrie-o (os dados antigos não podem ser encadeados depois):
docker compose down -v
docker compose up -d

./mvnw quarkus:dev
```

Aguarde `Listening on: http://localhost:8080` e confirme no log duas coisas: que o Hibernate criou a tabela
`audit_log` e a mensagem `Proteção append-only instalada em audit_log`.

> Em `quarkus:dev` a aplicação usa a chave HMAC de desenvolvimento e avisa isso no log. Fora do modo dev,
> defina `AUDITGOV_HMAC_KEY` e também `AUDITGOV_JWT_PUBLIC_KEY_LOCATION` e `AUDITGOV_JWT_ISSUER` (sem elas o
> jar não sobe; para o emissor de laboratório use `publicKey.pem` e `https://auditgov.local/dev-issuer`).

**Token de acesso.** A API exige um token JWT. Quem o emite, em produção, é o provedor de identidade; aqui,
o script `scripts/mint-token.sh` faz o papel dele com um par de chaves de **laboratório** (o token vale 1 hora):

```bash
export TOKEN=$(./scripts/mint-token.sh fiscal_maria)          # papel "operador"
export TOKEN_AUDITOR=$(./scripts/mint-token.sh auditora_ana auditor)   # papel "auditor"
```

---

## 1. Requisição autenticada

O operador gravado na trilha é a claim `upn` do token **validado** (assinatura, emissor e validade).

```bash
curl -i -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"cpf":"12345678900"}'
```

Resposta esperada (HTTP 200):

```json
{"cpf":"12345678900","nome":"Joana da Silva Pereira","dataNascimento":"1988-04-12","endereco":"Rua das Acácias, 120 - Centro - Campinas/SP - 13010-000"}
```

### 1.1 Capturando o Trace ID direto na chamada

O cliente pode **impor** o trace (W3C Trace Context), o que elimina a necessidade de procurá-lo depois:

```bash
TRACEID=$(openssl rand -hex 16)
echo "TraceID gerado: $TRACEID"

curl -s -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "traceparent: 00-${TRACEID}-$(openssl rand -hex 8)-01" \
  -d '{"cpf":"12345678900"}' | jq .
```

O mesmo `$TRACEID` deve aparecer no PostgreSQL **e** na URL do Jaeger.

### 1.2 Sem token, ou com token inválido: 401

```bash
# sem token
curl -i -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" -d '{"cpf":"98765432100"}'

# token com a assinatura adulterada (troca o último caractere)
curl -i -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" -H "Authorization: Bearer ${TOKEN%?}x" \
  -d '{"cpf":"98765432100"}'
```

Esperado: HTTP 401 e **nenhum dado** no corpo. Uma requisição recusada não chega ao método anotado e, por isso,
**não gera evidência de acesso a dado**; a tentativa fica só no span HTTP (status 401) do rastro. É um limite
deliberado desta versão (veja "Limites conhecidos" no README).

### 1.3 O cabeçalho `X-User-ID` não identifica mais ninguém

```bash
curl -s -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" \
  -H "X-User-ID: alguem_forjado" -d '{"cpf":"12345678900"}' > /dev/null
```

A evidência gravada tem `operator_id = fiscal_maria` (o do token); nenhuma linha tem `alguem_forjado`.

### 1.4 Falha: CPF inexistente

```bash
curl -i -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"cpf":"00000000000"}'
```

HTTP 404 e, ainda assim, uma evidência com `action = READ_CITIZEN_DATA_FAILED`, porque a tentativa de
acesso (já autenticada) é fato auditável.

### 1.5 Falha: CPF fora do formato

```bash
curl -i -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"cpf":"abc"}'
```

HTTP 400 e evidência `READ_CITIZEN_DATA_FAILED` com `subject_ref` vazio (não há titular a referenciar).

### 1.6 Segundo endpoint auditado

```bash
curl -s -X POST http://localhost:8080/api/v1/beneficios/consulta \
  -H "Content-Type: application/json" -H "Authorization: Bearer $(./scripts/mint-token.sh analista_jose)" \
  -d '{"cpf":"12345678900"}' | jq .
```

Gera evidência com `action = READ_BENEFIT_DATA`, sem nenhum código de auditoria em `BenefitResource`.

---

## 2. Validação no PostgreSQL

```bash
docker exec -it auditgov-postgres psql -U audit_user -d auditgov_db
```

Últimos registros:

```sql
SELECT seq, trace_id, span_id, operator_id, action, resource, subject_ref, payload_hash, event_timestamp
FROM audit_log
ORDER BY seq DESC
LIMIT 5;
```

Confira que `resource` é o **modelo de rota** (`POST /api/v1/cidadaos/consulta`) e que o CPF não aparece em
nenhuma coluna.

Busca pelo trace específico (usando o `$TRACEID` do passo 1.1):

```sql
SELECT * FROM audit_log WHERE trace_id = '<cole_o_trace_id_aqui>';
```

Trilha por operador (o do token validado):

```sql
SELECT operator_id, action, resource, event_timestamp
FROM audit_log
WHERE operator_id = 'fiscal_maria'
ORDER BY event_timestamp DESC;
```

### 2.1 Trilha por titular (direito de acesso, Art. 18 da LGPD)

O CPF não está no banco; a trilha é consultada pela referência HMAC dele, calculada com a mesma chave da
aplicação:

```bash
REF=$(./scripts/subject-ref.sh 12345678900)   # use AUDITGOV_HMAC_KEY se a aplicação usa outra chave
docker exec -it auditgov-postgres psql -U audit_user -d auditgov_db \
  -c "SELECT operator_id, action, resource, event_timestamp FROM audit_log WHERE subject_ref = '$REF' ORDER BY event_timestamp DESC;"
```

---

## 3. Cadeia de hash (o registro detecta adulteração)

Cada registro guarda `seq` (posição 1, 2, 3, ... sem buracos), `prev_hash` (o `record_hash` do registro anterior;
o primeiro usa 64 zeros) e `record_hash` (HMAC-SHA-256, com chave fora do banco, do conteúdo do registro). A
cadeia **não impede** a alteração: torna-a **detectável**.

### 3.1 Conferindo o encadeamento a olho, em SQL

```sql
SELECT seq,
       left(prev_hash, 12)   AS prev,
       left(record_hash, 12) AS record,
       prev_hash = lag(record_hash) OVER (ORDER BY seq) AS prev_confere
FROM audit_log ORDER BY seq DESC LIMIT 6;
```

`prev_confere` deve ser `true` em todas as linhas (só a linha `seq = 1`, se aparecer, mostra nulo, pois não tem
anterior). Isto confere só o **elo**; recalcular o HMAC exige a chave (itens 3.2 e 3.3).

### 3.2 Verificação pelo endpoint do auditor

```bash
curl -s -H "Authorization: Bearer $TOKEN_AUDITOR" \
  http://localhost:8080/api/v1/auditoria/cadeia/verificacao | jq .
```

Esperado: `"valida": true`, o número de registros verificados e o par `ultimoSeq`/`ultimoHash` (a cabeça da
cadeia). Com o token de `operador` a resposta é 403; sem token, 401.

### 3.3 Verificação independente, fora da aplicação

```bash
AUDITGOV_HMAC_KEY='<a chave da aplicação>' ./scripts/verify-chain.sh
```

Recalcula tudo com `psql` e `openssl`, sem usar o código Java da aplicação. É lento (um processo por linha):
use em tabelas pequenas ou médias.

### 3.4 Âncora externa (para detectar a remoção das últimas linhas)

Guarde **fora do banco** o par `(ultimoSeq, ultimoHash)` do item 3.2 (por exemplo, em um arquivo assinado ou em
outro sistema). Mais tarde:

```bash
curl -s -H "Authorization: Bearer $TOKEN_AUDITOR" \
  "http://localhost:8080/api/v1/auditoria/cadeia/verificacao?ancoraSeq=<seq>&ancoraHash=<hash>" | jq .
# ou: ./scripts/verify-chain.sh --ancora-seq <seq> --ancora-hash <hash>
```

Sem âncora, apagar as **últimas** linhas deixa uma cadeia mais curta e **válida**: só a âncora revela isso
(`TAIL_TRUNCATED`). Quem tem a chave **e** acesso de escrita ao banco pode refazer a cadeia inteira; a âncora
guardada antes também expõe isso (`ANCHOR_MISMATCH`).

### 3.5 Demonstração de adulteração (opcional, manual, só em banco de demonstração)

O dono da tabela pode desligar o gatilho. Esta demonstração mostra que a adulteração, **embora possível**, é
**detectada**, e depois desfaz a alteração:

```bash
docker exec -i auditgov-postgres psql -U audit_user -d auditgov_db <<'SQL'
SELECT operator_id FROM audit_log WHERE seq = 1;      -- anote o valor original
ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update_delete;
UPDATE audit_log SET operator_id = 'adulterado' WHERE seq = 1;
ALTER TABLE audit_log ENABLE TRIGGER audit_log_no_update_delete;
SQL

curl -s -H "Authorization: Bearer $TOKEN_AUDITOR" http://localhost:8080/api/v1/auditoria/cadeia/verificacao | jq .
```

Esperado: `"valida": false`, `"seqComFalha": 1`, `"motivo": "RECORD_HASH_MISMATCH"`. Restaure o valor original
(troque `<original>` pelo que você anotou) e confira que a verificação volta a `true`:

```bash
docker exec -i auditgov-postgres psql -U audit_user -d auditgov_db <<'SQL'
ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update_delete;
UPDATE audit_log SET operator_id = '<original>' WHERE seq = 1;
ALTER TABLE audit_log ENABLE TRIGGER audit_log_no_update_delete;
SQL
```

O resultado diz **que** a cadeia foi alterada e **onde**, não **quem** a alterou. Os testes automatizados
(`CadeiaDeHashTest`) cobrem alteração, remoção do meio, remoção do fim (com e sem âncora) e reordenação.

---

## 4. Validação no Jaeger

1. Abra `http://localhost:16686`.
2. **Caminho direto (recomendado):** cole a URL `http://localhost:16686/trace/<trace_id>` usando o valor da
   coluna `trace_id`.
3. **Caminho pela busca:** em *Service*, selecione `auditgov`; em *Operation*, `POST /api/v1/cidadaos/consulta`;
   clique em *Find Traces*. Os 32 caracteres hexadecimais do *Trace ID* devem ser idênticos aos do banco.
4. Expanda o span filho **`auditgov.evidence`** e abra a aba *Tags*:

| Tag | Valor esperado |
|---|---|
| `audit.operator` | `fiscal_maria` (o `upn` do token validado) |
| `audit.action` | `READ_CITIZEN_DATA` |
| `audit.resource` | `POST /api/v1/cidadaos/consulta` |
| `audit.subject_ref` | HMAC de 64 caracteres (igual à coluna `subject_ref`) |
| `audit.payload.hmac` | HMAC de 64 caracteres (igual à coluna `payload_hash`) |
| `audit.persisted` | `true` (`false` indica que a evidência não foi gravada) |

5. **O batimento (o ponto central da PoC):** o `audit.payload.hmac` exibido no Jaeger deve ser idêntico à
   coluna `payload_hash` da linha correspondente no PostgreSQL, e o `span_id` do banco deve casar com o
   *Span ID* do span `auditgov.evidence`.

### 4.1 O CPF não pode aparecer no rastro

```bash
curl -s "http://localhost:16686/api/traces/$TRACEID" | grep -c "12345678900"
```

O resultado esperado é `0`. Confira também, na interface, que nenhuma tag do span HTTP (`url.path`,
`http.route`) contém o CPF: ele viaja só no corpo da requisição.

### 4.2 Verificação independente do conteúdo

Quem tem a chave HMAC pode recalcular o `payload_hash` a partir do JSON recebido:

```bash
curl -s -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"cpf":"12345678900"}' \
  | ./scripts/verify-payload-hash.sh
```

> O valor só coincide se os bytes do JSON forem idênticos aos serializados pela aplicação. Para a defesa do
> trabalho, o argumento principal é o batimento Jaeger x PostgreSQL do item 5; a recomputação externa é
> sensível a formatação e exige a chave.

---

## 5. Dois serviços, um trace (fachada)

A fachada (`facade/`, porta 8081) recebe a consulta e a repassa ao serviço de cidadãos. O que se quer ver é
**um trace só** no Jaeger, com spans dos dois serviços, e o **mesmo `trace_id`** em `audit_log`.

```bash
# terminal 3 (os terminais 1 e 2 são o docker compose e o serviço de cidadãos)
cd facade && ../mvnw quarkus:dev -Ddebug=false

# em outro terminal, na raiz do projeto
./scripts/e2e-trace.sh fiscal_maria
```

O script imprime, com um `trace_id` escolhido por ele: o HTTP da fachada, a linha de `audit_log` (com o
operador do token) e os **serviços e operações do trace no Jaeger**. O código de saída é 0 só se tudo conferir.
Abra a URL impressa no final e capture a tela do trace mostrando **`auditgov-facade` e `auditgov`** juntos
(figura do relatório). Detalhes e limites em [`facade/README.md`](./facade/README.md).

---

## 6. Proteção contra alteração no banco

O gatilho `append-only` deve recusar qualquer alteração, mesmo vinda de um cliente SQL. (O primeiro comando
mantém o mesmo valor, de modo que nada muda caso a proteção esteja desligada.)

```bash
docker exec -it auditgov-postgres psql -U audit_user -d auditgov_db -c \
  "UPDATE audit_log SET operator_id = operator_id WHERE trace_id = '<trace_id>';"
```

Esperado: `ERROR: audit_log e append-only: operacao UPDATE bloqueada`. O mesmo vale para `DELETE FROM audit_log ...`
e `TRUNCATE audit_log`.

**Limite:** o dono da tabela e o superusuário podem remover o gatilho (`DROP TRIGGER`), como o item 3.5 mostra.
Para fechar essa lacuna, aplique `db/audit-app-role.sql` e rode a aplicação com o papel `auditgov_app`, que só
tem `INSERT` e `SELECT` (a cadeia de hash funciona com esse papel). A cadeia de hash cobre o que o gatilho
sozinho não cobre: **detectar** a alteração feita por quem consegue desligá-lo.

---

## 7. Falha do banco durante a gravação (opcional, manual)

Com a aplicação no ar, derrube o PostgreSQL e faça uma requisição:

```bash
docker stop auditgov-postgres
curl -i -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN" -d '{"cpf":"12345678900"}'
docker start auditgov-postgres
```

- **`auditgov.failure-mode=lenient` (padrão):** HTTP 200 com o dado, e no log da aplicação a mensagem
  `FALHA AO GRAVAR EVIDÊNCIA DE AUDITORIA`. **A evidência foi perdida sem aviso ao usuário.**
- **`auditgov.failure-mode=strict`** (`./mvnw quarkus:dev -Dauditgov.failure-mode=strict`): HTTP 503 e o dado
  não é entregue.

Ambos os comportamentos têm teste automatizado (`FailureModeLenientTest` e `FailureModeStrictTest`).

---

## 8. Checklist de evidências de conformidade

"Atendido" descreve o que o desenho e os testes pretendem garantir; **só vale como evidência depois que você
executa o passo indicado** e guarda a saída.

| Requisito | Onde comprovar | Situação |
|---|---|---|
| Rastreabilidade ponta a ponta (W3C Trace Context) | `trace_id` idêntico no PostgreSQL e no Jaeger (passo 4); um trace com dois serviços (passo 5) | Atendido para um serviço; para dois serviços, depende da execução do passo 5 |
| Identificação do operador (Art. 6º, X) | `operator_id` = `upn` do token validado (passos 1.1 a 1.3) | Atendido para quem **detém um token válido**; o token prova uma credencial, não a pessoa (pode ser emprestado) |
| Minimização de dados (Art. 6º, III) | `audit_log` sem CPF, nome ou endereço; só `subject_ref` e HMAC; CPF fora da URL e do Jaeger (passos 2 e 4.1) | Atendido; `subject_ref` é dado pseudonimizado, não anônimo |
| Integridade do que foi entregue | `payload_hash` (HMAC-SHA-256) replicado no span | Atendido |
| Proteção contra alteração do registro | Gatilho append-only (`ImmutabilityTest`, passo 6) | Atendido no banco, com a ressalva do dono da tabela |
| Detecção de adulteração do registro | Cadeia de hash (passo 3; `CadeiaDeHashTest`) | Atendido: **detecta**, não impede; a remoção das últimas linhas só com âncora externa |
| Registro de tentativas malsucedidas | `action = READ_CITIZEN_DATA_FAILED` (passos 1.4 e 1.5) | Atendido para tentativas que chegam ao método; **401 não gera evidência de acesso a dado** (só span HTTP) |
| Consulta por titular (Art. 18) | `subject_ref` (passo 2.1) | Atendido para consulta técnica; não há processo de atendimento ao titular |
| Evidência mantida diante de falha de gravação | `auditgov.failure-mode=strict` (passo 7) | Atendido só no modo strict |
| Retenção e descarte | n/d | **Não atendido** |
| Controle de acesso à trilha | Verificação da cadeia restrita ao papel `auditor` (passo 3.2) | **Parcial**: só o endpoint de verificação tem controle por papel; a leitura direta de `audit_log` no banco não |
