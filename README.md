# AuditGov

Prova de conceito de observabilidade e auditoria para APIs Quarkus. Cada acesso a um endpoint anotado com
`@Auditable` gera, de forma transversal (sem código de auditoria no recurso de negócio):

- uma **evidência** em uma tabela do PostgreSQL, protegida contra alteração e **encadeada por hash** (uma
  alteração posterior fica detectável);
- um **span** OpenTelemetry, visível no Jaeger, com o **mesmo `trace_id`** da evidência.

O acesso exige um **token JWT validado**: o operador gravado na evidência é o do token, não um cabeçalho livre.

Ela apoia o registro das operações de tratamento previsto no Art. 37 da LGPD. **Não garante conformidade
por si só**: veja "Limites conhecidos" abaixo.

## Como executar

```bash
# Esta versão mudou o esquema de audit_log. Se você já tinha o banco de uma versão anterior, recrie-o
# (os registros antigos não podem ser encadeados depois): docker compose down -v
docker compose up -d
./mvnw quarkus:dev
```

Exemplo (o CPF vai no **corpo**, nunca na URL; o token vem do emissor de **laboratório**):

```bash
TOKEN=$(./scripts/mint-token.sh fiscal_maria)
curl -X POST http://localhost:8080/api/v1/cidadaos/consulta \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"cpf":"12345678900"}'
```

Endpoints (dados fictícios, todos exigem token): `POST /api/v1/cidadaos/consulta` e
`POST /api/v1/beneficios/consulta`. A verificação da cadeia de hash,
`GET /api/v1/auditoria/cadeia/verificacao`, exige o papel `auditor`. Contrato OpenAPI em `/q/openapi` e
Swagger UI em `/q/swagger-ui`.

Roteiro completo de validação (PostgreSQL + Jaeger) em [`ROTEIRO-VALIDACAO.md`](./ROTEIRO-VALIDACAO.md);
o script [`test-audit.sh`](./test-audit.sh) executa os passos principais. O segundo serviço, que mostra o
mesmo trace atravessando dois serviços, está em [`facade/`](./facade/README.md).

## Autenticação (JWT)

A aplicação só **valida** tokens (assinatura RS256, emissor e validade); quem os emite, em produção, é o
provedor de identidade. Sem token válido a resposta é **401** e o método nem é chamado. O operador gravado é o
principal do token (claim `upn`; na falta, `preferred_username`; depois, `sub`) e o papel vem da claim `groups`
(`operador`, `auditor`). O cabeçalho `X-User-ID` **foi removido** e, se enviado, é ignorado.

Nesta PoC o emissor é **simulado**: `scripts/mint-token.sh` assina com um par de chaves de laboratório
(`src/test/resources/jwt/`, **não use fora do laboratório**) cuja chave pública a aplicação já confia
(`src/main/resources/publicKey.pem`). O par privado está no repositório de propósito, para que testes e
scripts funcionem logo após o clone; por isso **só os modos dev e test confiam nele por padrão**. Em modo de
produção (`java -jar`) a aplicação **não sobe** sem `AUDITGOV_JWT_PUBLIC_KEY_LOCATION` e `AUDITGOV_JWT_ISSUER`,
que devem apontar para a chave (ou o JWKS) e o emissor do IdP real. Para usar o emissor de laboratório no jar,
como no `bench/`, declare-o explicitamente; a aplicação sobe e avisa no log.

## O que é gravado

| Campo | Conteúdo |
|---|---|
| `seq` | Posição na cadeia de hash (1, 2, 3, ... sem buracos), única |
| `trace_id`, `span_id` | Identificadores W3C Trace Context (ligação com o Jaeger) |
| `operator_id` | Operador do **token JWT validado** (limpo e limitado a 120 caracteres) |
| `action` | Ação anotada, por exemplo `READ_CITIZEN_DATA`; falhas recebem o sufixo `_FAILED` |
| `resource` | Método HTTP + **modelo** de rota (`POST /api/v1/cidadaos/consulta`), nunca o caminho real |
| `subject_ref` | HMAC-SHA-256 do CPF consultado (pseudonimização, LGPD Art. 13, §4º); permite consulta por titular |
| `payload_hash` | HMAC-SHA-256 do conteúdo entregue |
| `event_timestamp` | Momento do evento (UTC, microssegundos) |
| `prev_hash`, `record_hash` | Encadeamento: `prev_hash` é o `record_hash` do registro anterior (o primeiro usa 64 zeros); `record_hash` é o HMAC-SHA-256 do conteúdo do registro |

O CPF, o nome e o endereço **não** são gravados em claro, nem no banco nem nas tags dos spans.

## Cadeia de hash

O gatilho do banco **impede** `UPDATE`/`DELETE`/`TRUNCATE`, mas o dono da tabela pode desligá-lo. A cadeia de hash
cobre essa lacuna de outro jeito: **não impede** a alteração, torna-a **detectável**. Cada registro leva o hash do
anterior e o seu próprio, calculado com uma chave (derivada de `auditgov.hmac.key`) que **não fica no banco**.

- **Verificação:** `GET /api/v1/auditoria/cadeia/verificacao` (papel `auditor`) ou, de forma independente e
  fora da aplicação, `AUDITGOV_HMAC_KEY=... ./scripts/verify-chain.sh`. O resultado é `valida` (sim/não) e, se não,
  a posição (`seq`) e o motivo (`SEQ_GAP`, `PREV_HASH_MISMATCH`, `RECORD_HASH_MISMATCH`, `ANCHOR_MISMATCH`,
  `TAIL_TRUNCATED`).
- **Âncora externa:** apagar as **últimas** linhas deixa uma cadeia mais curta e válida. Guarde fora do banco o par
  `(seq, hash)` da cabeça da cadeia e passe-o depois (`?ancoraSeq=&ancoraHash=` ou `--ancora-seq/--ancora-hash`).
- **Concorrência e custo:** cada gravação de evidência toma um bloqueio consultivo do PostgreSQL
  (`pg_advisory_xact_lock`) até confirmar, para que a cadeia não tenha buracos nem bifurcações. **As gravações de
  evidência são serializadas**: há um teto de vazão com auditoria. Meça no seu ambiente (`bench/`).
- **Formato canônico** (v1, usado pelo código Java, pelo `verify-chain.sh` e documentado em `AuditChain`): campos
  separados por LF, com o timestamp em microssegundos desde 1970 UTC.

## Configuração (`application.properties`)

| Propriedade | Padrão | Efeito |
|---|---|---|
| `auditgov.enabled` | `true` | `false` desliga o interceptador (linha de base de desempenho). |
| `auditgov.failure-mode` | `lenient` | `lenient`: se a evidência não puder ser gravada, registra ERROR no log e responde (há perda de evidência). `strict`: responde 503 e não entrega o dado. |
| `auditgov.append-only.enabled` | `true` | Instala no PostgreSQL o gatilho que bloqueia `UPDATE`, `DELETE` e `TRUNCATE` em `audit_log`. |
| `auditgov.hmac.key` | variável `AUDITGOV_HMAC_KEY` | Chave-mestra do HMAC (subchaves para `subject_ref`, `payload_hash` e a cadeia). Em modo de produção a aplicação **não sobe** com a chave de desenvolvimento. A chave nunca deve ficar no banco e **deve ser a mesma durante toda a vida de um banco**: com outra chave, a verificação da cadeia acusa divergência. |
| `mp.jwt.verify.publickey.location` | dev/test: `publicKey.pem`; produção: variável `AUDITGOV_JWT_PUBLIC_KEY_LOCATION`, **sem padrão** | Chave pública do emissor dos tokens (em produção, a chave ou o JWKS do IdP). Sem a variável, o jar não sobe. |
| `mp.jwt.verify.issuer` | dev/test: `https://auditgov.local/dev-issuer`; produção: variável `AUDITGOV_JWT_ISSUER`, **sem padrão** | Emissor aceito. |

## Testes

Os testes de integração usam o PostgreSQL do `docker compose` e um banco **separado**, `auditgov_test`
(para o `drop-and-create` dos testes nunca apagar as evidências do banco de demonstração).

```bash
docker compose up -d
# Volume já existente de uma versão anterior? Crie o banco de testes uma vez:
docker exec auditgov-postgres psql -U audit_user -d postgres -c "CREATE DATABASE auditgov_test"
./mvnw test
```

A tabela é somente-anexo, então os testes **não apagam linhas**: cada um usa um operador único.

| Camada | Classes |
|---|---|
| Lógica pura (sem Quarkus) | `HmacUtilsTest`, `OperatorIdsTest`, `CpfsTest`, `RouteTemplatesTest`, `AuditChainTest`, `JwtMinterTest` |
| Integração (`@QuarkusTest`) | `CitizenResourceTest`, `BenefitResourceTest`, `ImmutabilityTest`, `FailureModeLenientTest`, `FailureModeStrictTest`, `AuditDisabledTest`, `ConcurrencyTest`, `MetodoNaoAnotadoTest`, `AutenticacaoTest`, `CadeiaDeHashTest` |

`AutenticacaoTest` prova o que a aplicação **recusa** (sem token, outra chave, expirado, outro emissor, `alg=none`,
claim adulterada, credencial que não é Bearer) e o que **aceita**. `CadeiaDeHashTest` prova a cadeia e a
detecção de alteração, remoção do meio e do fim (com e sem âncora). A fachada tem os seus próprios testes
(veja [`facade/README.md`](./facade/README.md)).

Nos testes de falha do banco, o log mostra uma mensagem `ERROR` com *stack trace*
(`FALHA AO GRAVAR EVIDÊNCIA DE AUDITORIA`): é o comportamento esperado, a falha é simulada.

## Estrutura

```
src/main/java/br/gov/auditgov/
├── annotation/    -> @Auditable e AuditSubjectProvider (contrato declarativo)
├── domain/        -> AuditLog (entidade Panache)
├── dto/           -> CitizenDTO, BenefitDTO, CpfQuery, ChainVerificationDTO
├── interceptor/   -> AuditInterceptor (núcleo) e AuditUnavailableException
├── resource/      -> CitizenResource e BenefitResource (endpoints fictícios), AuditChainResource
│                     (verificação da cadeia) e OpenApiSecurity (esquema Bearer do OpenAPI)
├── service/       -> AuditEvidenceStore (contrato de gravação) e AuditLogService (gravação REQUIRES_NEW,
│                     bloqueio e encadeamento), ChainVerifierService, AuditCrypto (HMAC),
│                     AuditSettings (configuração), AuditSchemaGuard (gatilho append-only)
└── util/          -> AuditChain (formato canônico e verificador, Java puro), HmacUtils, OperatorIds,
                      Cpfs, RouteTemplates
src/main/resources/db/audit-append-only.sql   -> gatilhos append-only (fonte única)
src/main/resources/publicKey.pem              -> chave pública do emissor de laboratório
src/test/resources/jwt/                       -> chave privada de LABORATÓRIO (só testes e scripts)
db/audit-app-role.sql                          -> papel de banco só com INSERT/SELECT (endurecimento)
facade/                                        -> segundo serviço (projeto Maven independente)
bench/                                         -> medição de desempenho (k6) e de armazenamento (SQL)
scripts/                                       -> mint-token.sh, verify-chain.sh, e2e-trace.sh, subject-ref.sh,
                                                  verify-payload-hash.sh, export-openapi.sh
```

## Scripts úteis

- `scripts/mint-token.sh <operador> [papeis] [validade_s]`: emite um token JWT de **laboratório** (RS256).
- `scripts/verify-chain.sh`: verificação **independente** (psql + openssl) da cadeia de hash; aceita `--ancora-seq/--ancora-hash`.
- `scripts/e2e-trace.sh`: uma chamada à fachada e a conferência de um trace com os dois serviços (precisa dos dois no ar).
- `scripts/subject-ref.sh <cpf>`: calcula o `subject_ref` de um CPF para consultar "quem acessou os dados deste titular" (Art. 18).
- `scripts/verify-payload-hash.sh`: recalcula o `payload_hash` de um conteúdo (precisa da chave).
- `scripts/export-openapi.sh`: salva o contrato OpenAPI **real** em `docs/` (Anexo A do relatório).
- `bench/run-benchmark.sh`: medição de latência e vazão com e sem auditoria, com verificação da cadeia (veja [`bench/README.md`](./bench/README.md)).
- `bench/measure-storage.sql`: mede, num banco descartável, o tamanho em disco por evento de auditoria.

## Limites conhecidos

- **O token prova uma credencial válida, não a pessoa.** O operador vem do token validado; quem o detém (ou o recebeu
  emprestado) é aceito. Nesta PoC o emissor é simulado. Em produção, o emissor é o IdP e a confiança passa a depender
  dele.
- **Requisição recusada (401) não gera evidência de acesso a dado.** Ela não chega ao método anotado; a tentativa fica
  só no span HTTP do rastro (status 401). Auditar tentativas de acesso negado exigiria um registro de segurança à parte.
- **A cadeia de hash detecta, não impede.** Quem tem a chave **e** acesso de escrita ao banco pode refazer a cadeia
  inteira; a âncora externa, guardada antes e fora do banco, expõe isso. A remoção das **últimas** linhas só é
  detectada com âncora. O resultado diz se a cadeia está íntegra, **não quem** a alterou. Não há rotação de chave.
- **O encadeamento serializa as gravações.** O bloqueio que evita buracos e bifurcações limita a vazão com auditoria.
  O custo real depende do ambiente e precisa ser medido.
- **Proteção do registro no banco, com ressalva.** O gatilho bloqueia `UPDATE`/`DELETE`/`TRUNCATE`, mas o dono da tabela
  e o superusuário podem removê-lo. Para fechar essa lacuna, rode a aplicação com o papel de `db/audit-app-role.sql` e
  mantenha o dono fora dela (a cadeia funciona com esse papel).
- **Registros anteriores à cadeia não podem ser encadeados depois.** Quem atualiza de uma versão anterior recria o banco.
- **Falha de gravação.** No modo `lenient` (padrão) uma falha do banco causa perda de evidência sem aviso ao usuário.
  Use `strict` se isso não for aceitável.
- **Cobertura da trilha.** Só são auditadas as requisições que chegam ao método anotado; corpos que o framework rejeita
  antes (por exemplo, JSON malformado) não geram evidência.
- **Sem retenção nem controle de acesso amplo à trilha.** Não há política de retenção; só o endpoint de verificação tem
  controle por papel, e a leitura direta de `audit_log` no banco não.
- **A fachada é uma demonstração.** Ela não valida o token (quem valida é o serviço de cidadãos) e o repasse do token é uma
  simplificação; veja [`facade/README.md`](./facade/README.md).
- **Desempenho não medido.** Use `bench/` para medir antes de afirmar qualquer coisa.

## Nota sobre o Maven Wrapper

Este projeto assume `./mvnw` (Maven Wrapper). Se o seu ambiente não tiver os arquivos do wrapper (`mvnw`,
`mvnw.cmd`, `.mvn/wrapper/`), gere-os com:

```bash
mvn -N io.quarkus:quarkus-maven-plugin:3.15.1:create -Dwrapper
```

ou, tendo o Maven instalado localmente, basta substituir `./mvnw` por `mvn` em todos os comandos.

## Documentos do TG I

A fase anterior do projeto (TG I) continua neste repositório:

- `TG I - AuditGov - Rafael Sabbadini Galego (Versão Final ABNT).docx`
- `Engenharia de Requisitos -  AuditGov.docx`
- `Banner-Rafael-Versao-Final.pptx`
- `Diagrama de Atividades - AuditGov.png`, `Diagrama de Sequência - AuditGov.png`,
  `Diagrama de classes - AuditGov.png`

O README original do TG I (problema, proposta e metodologia) está no histórico do git, no commit `afbddc5`.
