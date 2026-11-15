# AuditGov v3: notas do patch

Este pacote é o código que o relatório TG-II **v4** descreve. Ele soma ao patch v2 o que faltava nos itens
do orientador que dependem de código: **A2** (cadeia de hash e verificação), **A3** (JWT validado no lugar do
`X-User-ID`, mais um segundo serviço que mostra o mesmo trace atravessando dois serviços) e **A4** (roteiro de
medição refeito para o novo desenho). É **opcional**: sem ele, o relatório v3 continua descrevendo o projeto
original. Com ele, use a v4.

**Leia antes.** Nada aqui foi compilado nem executado contra o Quarkus 3.15.1 real: o ambiente em que
trabalhei não alcança o Maven Central. A seção 5 separa o que verifiquei do que só a sua execução comprova, e a
seção 6 lista onde o erro pode ser meu. Nenhum número de desempenho existe até você medir.

## 1. O que o patch muda (v2 → v3)

| Item do orientador | O que mudou | Onde ver | Situação |
|---|---|---|---|
| **A1** CPF fora da trilha | O mecanismo é o da v2 (CPF no corpo; `resource` com o modelo de rota; titular só como `subject_ref`). Novo: o operador gravado deixa de vir de um cabeçalho livre e passa a ser o do token validado. | `AuditInterceptor`, `OperatorIds` | Escrito; integração não executada |
| **A2** imutabilidade de verdade | **Cadeia de hash.** Cada registro guarda `seq`, `prev_hash` e `record_hash` (HMAC-SHA-256 com subchave derivada de `AUDITGOV_HMAC_KEY`, fora do banco). A gravação toma um bloqueio consultivo do PostgreSQL para que a cadeia não tenha buracos nem bifurcações. Verificação por endpoint (papel `auditor`) e por script independente (`psql` + `openssl`), com **âncora externa** opcional. Testes que adulteram, removem e conferem. O gatilho da v2 continua. | `AuditChain`, `AuditLog`, `AuditLogService`, `ChainVerifierService`, `AuditChainResource`, `scripts/verify-chain.sh`, `AuditChainTest`, `CadeiaDeHashTest` | Lógica pura executada; script conferido contra o Java num PostgreSQL 16; integração não executada |
| **A3** JWT | `quarkus-smallrye-jwt`; `@Authenticated` nos endpoints de dados; sem token válido a resposta é 401 e o método nem é chamado; o operador é o principal do token; `X-User-ID` removido (ignorado se enviado); a verificação da cadeia exige o papel `auditor`. Emissor **simulado** (`scripts/mint-token.sh`, par de chaves de laboratório). | `application.properties`, `CitizenResource`, `BenefitResource`, `AuditChainResource`, `OpenApiSecurity`, `JwtMinter`, `AutenticacaoTest` | Escrito; emissão e conteúdo dos tokens de teste conferidos em Java puro; comportamento do smallrye-jwt não executado |
| **A3** 2º serviço | Projeto Maven independente `facade/` (porta 8081): `POST /api/v1/portal/consulta` repassa o `Authorization` e o `traceparent` ao serviço de cidadãos. `scripts/e2e-trace.sh` confere a chamada, a linha de `audit_log` com o mesmo `trace_id` e um trace no Jaeger com os dois serviços. | `facade/`, `scripts/e2e-trace.sh` | Escrito; testes contra um serviço de cidadãos simulado, não executados; **o trace com dois serviços só existe depois que você rodar** |
| **A4** desempenho e concorrência | `bench/` refeito: JWT em todos os modos, um token por usuário virtual, verificação da cadeia antes e depois, conferência "evidências novas = requisições enviadas", ambiente registrado. `bench/measure-storage.sql` com o esquema novo. `ConcurrencyTest` confere também que as 100 evidências têm `seq` consecutivos e cadeia íntegra. | `bench/`, `ConcurrencyTest` | Roteiro pronto; **nenhum número foi medido por você** |
| M2, M3, M4, M6 | Sem mudança de mecanismo (v2). Os testes de integração passaram a enviar um token; o OpenAPI ganhou o esquema de segurança Bearer. | `src/test`, `OpenApiSecurity` | Como na v2 |

### Testes por arquivo (contagem no código desta versão)

| Grupo | Classes e quantidade de testes | Roda sem Quarkus? |
|---|---|---|
| Lógica pura (50) | `HmacUtilsTest` 4, `OperatorIdsTest` 5, `CpfsTest` 3, `RouteTemplatesTest` 5, `AuditChainTest` 26, `JwtMinterTest` 7 | Sim |
| Integração, projeto principal (41) | `CitizenResourceTest` 9, `BenefitResourceTest` 2, `ImmutabilityTest` 3, `FailureModeLenientTest` 1, `FailureModeStrictTest` 2, `AuditDisabledTest` 1, `MetodoNaoAnotadoTest` 1, `ConcurrencyTest` 1, `AutenticacaoTest` 12, `CadeiaDeHashTest` 9 | Não (`@QuarkusTest`, precisam do PostgreSQL) |
| Fachada (17) | `TraceparentHeaderTest` 6 (puro); `PropagacaoTest` 9, `PropagacaoManualTest` 1, `ServicoIndisponivelTest` 1 (`@QuarkusTest`) | Parcialmente |

O número que vale para o relatório é o que o Maven imprimir na sua máquina (`Tests run: N`), não esta tabela.

### O que cada teste novo prova

| Ponto do orientador | Teste |
|---|---|
| 401 sem token e o dado não é entregue | `AutenticacaoTest.deveRecusarRequisicaoSemTokenENaoEntregarODado` (e `...SemTokenTambemNoEndpointDeBeneficios`) |
| Token falso ou inválido | `deveRecusarTokenAssinadoPorOutraChave`, `...Expirado`, `...DeOutroEmissor`, `...SemAssinaturaComAlgNone`, `...ComClaimAdulteradaDepoisDeAssinado`, `deveRecusarCredencialQueNaoEBearer` |
| Operador vem da claim, não de cabeçalho | `deveGravarComoOperadorOUpnDoTokenValidado`, `deveUsarOSubQuandoOTokenNaoTemUpn`, `deveIgnorarOCabecalhoXUserIdQuandoHaTokenValido` |
| Verificação só para o auditor | `deveExigirTokenEPapelAuditorParaVerificarACadeia` |
| Cadeia válida, encadeada e começando no gênese | `CadeiaDeHashTest.deveManterACadeiaValidaEContinuaAposVariasConsultas`, `cadaRegistroDeveGuardarOHashDoAnteriorEPoderSerRecalculadoAPartirDoBanco`, `oPrimeiroRegistroDeveApontarParaOHashGenese` |
| Adulteração detectada | `deveDetectarConteudoAlteradoPorQuemDesativaOGatilho`, `deveDetectarRegistroRemovidoNoMeioDaCadeia` |
| Remoção do fim só com âncora | `remocaoDosUltimosRegistrosSoApareceComAncoraGuardadaForaDoBanco`, `endpointDeveAceitarAncoraEAcusarAncoraDeOutraCadeia` |
| Rede de segurança contra bifurcação | `bancoDeveRecusarDoisRegistrosNaMesmaPosicaoDaCadeia` |
| UPDATE/DELETE/TRUNCATE recusados | `ImmutabilityTest` (v2) |
| 100 chamadas paralelas sem misturar contexto | `ConcurrencyTest` |
| Mesmo `trace_id` nos dois serviços | `facade`: `PropagacaoTest.deveRepassarOMesmoTraceIdAoServicoDeCidadaos` (contra um serviço simulado) e `scripts/e2e-trace.sh` (contra os dois serviços reais) |

## 2. Decisões de projeto, e por quê

1. **HMAC, e não SHA-256 simples, na cadeia.** O SHA-256 é público: quem altera uma linha e tem acesso de
   escrita refaz os hashes seguintes sem segredo nenhum. Com HMAC, refazer a cadeia exige a chave, que não fica
   no banco. O mesmo raciocínio vale para `payload_hash` e `subject_ref`.
2. **A cadeia detecta, não impede.** O gatilho recusa `UPDATE`/`DELETE`/`TRUNCATE`; a cadeia cobre o caso em
   que alguém com privilégio o contorna. Quem tem a chave **e** escrita no banco pode refazer a cadeia inteira.
   A **âncora externa** (o par `seq` + hash da ponta, guardado fora do banco) expõe isso e também a remoção das
   últimas linhas, que deixa uma cadeia mais curta e válida. O resultado diz se a cadeia está íntegra, **não
   quem** a alterou.
3. **Bloqueio consultivo (`pg_advisory_xact_lock`).** Duas gravações simultâneas não podem ler a mesma ponta da
   cadeia. O bloqueio vale até a confirmação e vale para todas as instâncias ligadas ao mesmo banco. O custo é
   real: **as gravações de evidência viram uma fila**, o que limita a vazão com auditoria. A restrição de
   unicidade de `seq` é a rede de segurança. Alternativas (uma cadeia por instância, ou ancorar blocos de
   registros em vez de cada registro) trocam esse custo por outra complexidade e ficam como trabalho futuro.
4. **A cadeia é uma lista ordenada por `seq` atribuído pela aplicação**, não uma `SEQUENCE` do banco: as
   sequências admitem buracos e não garantem a ordem de confirmação.
5. **O token prova uma credencial válida, não a pessoa.** O operador gravado vem de uma assinatura verificada,
   mas um token emprestado continua válido até expirar. O que mudou em relação ao `X-User-ID` é a origem do
   dado (uma claim assinada, não um cabeçalho que qualquer cliente escreve), não a certeza sobre quem digitou.
6. **Emissor simulado.** A aplicação só **valida** tokens (assinatura RS256, emissor e validade). Quem os emite,
   em produção, é o provedor de identidade. No laboratório, `scripts/mint-token.sh` faz esse papel com um par
   de chaves que está no repositório (`src/test/resources/jwt/`): serve a testes e demonstração, **não** a
   segurança. Não use esse par fora do laboratório.
7. **Fachada como projeto Maven irmão** (`facade/`), e não módulo: o `pom.xml` e a estrutura do projeto
   principal não mudam. Os dois serviços exportam spans para o mesmo Jaeger.
8. **`traceparent` é correlação, não autenticação nem autorização.** Ele só liga os trechos de uma mesma
   operação. A propagação automática depende da instrumentação do cliente REST do Quarkus; se ela não
   acontecer na sua versão, `facade.trace.manual-propagation=true` faz a fachada montar o cabeçalho a partir do
   span atual (o `PropagacaoManualTest` cobre esse caminho).
9. **A fachada repassa o token e não o valida**: quem valida é o serviço de cidadãos. É uma simplificação de
   demonstração; em produção, avalie troca de token ou audiência própria para cada serviço.

## 3. Alterações por arquivo (v2 → v3)

- **Novos (código):** `util/AuditChain`, `service/ChainVerifierService`, `dto/ChainVerificationDTO`,
  `resource/AuditChainResource`, `resource/OpenApiSecurity`, `src/main/resources/publicKey.pem`.
- **Alterados:** `pom.xml` (+`quarkus-smallrye-jwt`), `application.properties` (bloco JWT), `domain/AuditLog`
  (colunas da cadeia; `id` atribuído pela aplicação), `service/AuditLogService` (bloqueio e encadeamento),
  `service/AuditCrypto` (subchave e HMAC da cadeia), `interceptor/AuditInterceptor` (operador vem do
  `SecurityIdentity`), `resource/CitizenResource` e `BenefitResource` (`@Authenticated`), `util/OperatorIds`,
  `README.md`, `ROTEIRO-VALIDACAO.md`, `test-audit.sh`, `db/audit-app-role.sql` (só comentário), `bench/*`.
- **Novos (testes):** `AuditChainTest`, `CadeiaDeHashTest`, `security/JwtMinter`, `security/JwtMinterTest`,
  `security/AutenticacaoTest`, `src/test/resources/jwt/` (chave privada de **laboratório**). Os demais testes
  de integração passaram a enviar um token.
- **Novos (apoio):** `scripts/mint-token.sh`, `scripts/verify-chain.sh`, `scripts/e2e-trace.sh`.
- **Novo projeto:** `facade/` (fontes, testes e `README.md`).
- **Removidos:** nenhum.

## 4. Como aplicar e rodar

```bash
# 1. Aplicar sobre o projeto que você já tem (na raiz dele).
#    A) Você NÃO aplicou o patch v2 (projeto original): patch acumulado.
git apply --whitespace=nowarn auditgov_v3.patch
#    B) Você já aplicou o patch v2: só a diferença v2 -> v3.
git apply --whitespace=nowarn auditgov_v2_para_v3.patch
#    C) Sem git: apague src/, bench/, scripts/ e db/ e copie o conteúdo do auditgov_v3.zip por cima
#       (preserve .git, mvnw e .mvn).
chmod +x test-audit.sh scripts/*.sh bench/*.sh

# 2. Banco novo. O esquema de audit_log mudou (seq, prev_hash, record_hash) e os registros antigos
#    não podem ser encadeados depois. ATENÇÃO: isto apaga as evidências do banco de demonstração.
docker compose down -v
docker compose up -d

# 3. Testes (usam o banco auditgov_test, criado pelo db/init/01-test-db.sql)
./mvnw test
(cd facade && ../mvnw test)       # se ../mvnw não funcionar daí, use "mvn test" dentro de facade/

# 4. Demonstração: três terminais
./mvnw quarkus:dev                               # 1: serviço de cidadãos (porta 8080)
(cd facade && ../mvnw quarkus:dev -Ddebug=false) # 2: fachada (porta 8081)
./test-audit.sh                                  # 3: JWT, 401, PostgreSQL, Jaeger, cadeia e append-only
./scripts/e2e-trace.sh                           # 3: um trace com os dois serviços
./scripts/verify-chain.sh                        # 3: verificação independente da cadeia
```

**A chave do HMAC.** Em `quarkus:dev` a aplicação usa a chave de desenvolvimento e o `verify-chain.sh` também.
Fora disso (`java -jar`), defina `AUDITGOV_HMAC_KEY` com pelo menos 16 bytes, e **use a mesma chave durante toda a
vida de um banco**: com outra chave, a verificação da cadeia acusa divergência nos registros antigos. O script
independente lê a mesma variável: `AUDITGOV_HMAC_KEY=... ./scripts/verify-chain.sh`.

Para o relatório, rode também:

```bash
./mvnw package -DskipTests           # grava target/openapi/openapi.yaml e openapi.json (Anexo A)
./scripts/export-openapi.sh          # alternativa, com a aplicação no ar: grava em docs/
# captura de tela do Swagger UI em http://localhost:8080/q/swagger-ui
# captura de tela do Jaeger com UM trace e os dois serviços (a saída do e2e-trace.sh diz qual)
psql -U audit_user -d <banco_descartavel> -f bench/measure-storage.sql     # armazenamento por evento
```

e o roteiro de desempenho em `bench/README.md` (precisa de k6, `jq`, `curl` e `openssl`; um modo de cada vez,
com o banco do zero).

**Se o GitHub recusar o envio por causa da chave privada de laboratório**, ou se você preferir não versioná-la,
gere um par novo (a aplicação, os scripts e os testes passam a usá-lo sem outra mudança):

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/test/resources/jwt/auditgov-dev-private.pem
openssl rsa -in src/test/resources/jwt/auditgov-dev-private.pem -pubout -out src/main/resources/publicKey.pem
```

**Se algum teste falhar:** o erro pode ser meu. Mande a saída completa do teste que falhou.

## 5. O que foi verificado aqui e o que não foi

**Verificado aqui**

- Os 144 arquivos Java (código principal, testes, fachada e os stubs) compilam com `javac` 21, mas **contra
  stubs que eu escrevi** das bibliotecas externas (Quarkus, Jakarta, OpenTelemetry, RestAssured, segurança e JWT).
  Isso pega erros de sintaxe e de tipos do meu código; não garante compatibilidade com as APIs reais.
- Os **56 testes de lógica pura** passam (`HmacUtilsTest`, `OperatorIdsTest`, `CpfsTest`, `RouteTemplatesTest`,
  `AuditChainTest`, `JwtMinterTest`, `TraceparentHeaderTest`), executados por um executor mínimo, não pelo JUnit.
- **A cadeia**: o `verify-chain.sh` (`psql` + `openssl`) e o verificador Java usam o mesmo formato canônico.
  Conferi com linhas geradas pelo código Java e gravadas num PostgreSQL 16 (acentos e microssegundos
  incluídos). A alteração de conteúdo, a remoção de um registro do meio, a remoção do final (só com âncora) e a
  âncora que não confere foram detectadas com os motivos esperados.
- **SQL**: a demonstração de adulteração do roteiro (desligar o gatilho, alterar, religar, desfazer) e as
  consultas de conferência rodaram num PostgreSQL 16. Um papel só com `INSERT`/`SELECT` consegue tomar o
  bloqueio, ler a ponta da cadeia e inserir, e recebe `permission denied` em `UPDATE` e `DELETE`.
- **Mecânica dos scripts**, com serviços e `k6` **simulados**: `test-audit.sh`, `e2e-trace.sh`,
  `run-benchmark.sh` e a lógica de `auditgov-load.js`. Isso prova a lógica dos scripts, não o comportamento do
  Quarkus, do k6 real nem do Jaeger.

**Não verificado (depende de você rodar)**

- `./mvnw test` e `./mvnw quarkus:dev`: nenhum teste `@QuarkusTest` rodou (41 no projeto principal e 11 na
  fachada). Eles existem e compilam contra stubs, mas nunca foram executados contra o Quarkus de verdade.
- O comportamento real do `smallrye-jwt` (401 e 403, nome do principal, conferência do emissor, rejeição de
  `alg=none`), o DDL que o Hibernate gera (unicidade de `seq`, `id` atribuído), as APIs do Panache que o código usa.
- A injeção automática do `traceparent` pelo cliente REST no Quarkus 3.15, e o mapeamento de falha de conexão
  para a resposta 502 da fachada.
- **O trace com dois serviços no Jaeger** e a captura de tela dele; o OpenAPI gerado (Anexo A).
- O `k6` real e **todos os números de desempenho**; o custo de armazenamento na sua máquina.
- `../mvnw` a partir de `facade/`.

**Ordem de grandeza que vi aqui, só para você saber o que esperar. Não copie para o relatório.** São
experimentos de bancada com `pgbench` e SQL puro (PostgreSQL 16 num contêiner de teste), **sem a aplicação**: com o
bloqueio, 32 clientes simultâneos gravaram cerca de 1.300 evidências por segundo, nenhuma foi recusada e a
cadeia saiu íntegra; **sem** o bloqueio, a maioria das inserções simultâneas (35.492 de 38.097) bateu na
restrição de unicidade de `seq`, o que mostra por que o bloqueio existe. Com 90 clientes a vazão caiu para cerca de
1.100 por segundo e a latência média subiu para a casa de 80 ms, o efeito da fila. No armazenamento, 1 milhão de
eventos sintéticos no esquema novo ocuparam 765.689.856 bytes (tabela + índices), contra 406.003.712 do esquema
original (Tabela 3 da v3). O que vale para o relatório é o que `bench/` e `bench/measure-storage.sql` medirem na
sua máquina.

## 6. Onde o erro pode ser meu (pontos que dependem do Quarkus real)

1. `AutenticacaoTest` espera 401 para token ausente ou inválido e 403 para papel insuficiente. Se alguma
   resposta vier diferente, compare com o que o `smallrye-jwt` 3.15 devolve antes de mexer na aplicação.
2. O principal do token (`SecurityIdentity.getPrincipal().getName()`) deve seguir a ordem `upn` →
   `preferred_username` → `sub`. `deveUsarOSubQuandoOTokenNaoTemUpn` cobre o último caso.
3. Hibernate: `AuditLog` usa `id` atribuído pela aplicação e `persistAndFlush()`. Se aparecer erro de "detached
   entity" ou de restrição ao criar a tabela, é aqui.
4. Fachada: se `PropagacaoTest.deveRepassarOMesmoTraceIdAoServicoDeCidadaos` falhar porque o cliente REST não
   injeta o `traceparent`, ligue `facade.trace.manual-propagation=true` (e rode `PropagacaoManualTest`).
5. `QuarkusMock.installMockForType` sobre `AuditEvidenceStore` e a propriedade
   `quarkus.smallrye-openapi.store-schema-directory` (já eram pontos de atenção da v2).

## 7. O que muda no relatório

O relatório TG-II v4 acompanha este pacote e segue o mesmo princípio: só afirma o que foi executado. O que
depende da sua execução (resultado de `./mvnw test`, trace com dois serviços, números do `k6`, armazenamento,
Anexo A) aparece em destaque no texto para você preencher com o resultado real.

## 8. O que continua sem solução

- **O token não prova a pessoa**, e o emissor desta PoC é simulado.
- **Requisição recusada (401) não gera evidência de acesso a dado.** Ela não chega ao método anotado; a
  tentativa fica só no span HTTP. Auditar tentativas negadas exigiria um registro de segurança à parte.
- **A cadeia detecta, não impede**, e a remoção das últimas linhas só aparece com âncora guardada fora do banco.
  Não há rotação de chave, nem guarda automática da âncora.
- **O encadeamento serializa as gravações.** O custo real depende do ambiente e precisa ser medido.
- O dono da tabela e o superusuário podem remover o gatilho. Use `db/audit-app-role.sql` para separar os papéis.
- "Framework": hoje é uma anotação e um interceptador dentro da própria aplicação. Para ser reutilizável,
  precisa virar um módulo Maven separado. Sem isso, o texto deve dizer "prova de conceito".
- Não há política de retenção, controle de acesso amplo à trilha nem cobertura de testes medida (JaCoCo).
- No modo `lenient` (padrão), uma falha do banco perde a evidência sem avisar o usuário.
- Requisições que o framework rejeita antes de chegar ao método (por exemplo, JSON malformado) não geram evidência.
- A fachada é demonstração: não valida o token, e o repasse do token é uma simplificação.

## 9. Para explicar na defesa

- **O que é a cadeia de hash?** Cada registro guarda o hash do anterior e o seu próprio, calculado com uma chave
  que não está no banco. Mudar, apagar ou reordenar um registro quebra o encadeamento dos seguintes, e a
  verificação aponta a posição e o motivo.
- **Então os registros ficaram imutáveis?** Ficaram protegidos em duas camadas diferentes: o gatilho **impede**
  `UPDATE`/`DELETE`/`TRUNCATE`; a cadeia **detecta** a alteração de quem contornou o gatilho. Quem é dono da tabela
  pode remover o gatilho, e quem tem a chave e escrita no banco pode refazer a cadeia; a âncora externa revela isso.
- **Por que HMAC e não só SHA-256?** Com SHA-256 puro qualquer pessoa com escrita no banco refaz os hashes. Com
  HMAC, é preciso a chave. Para dados de domínio pequeno (CPF, nome), o HMAC também impede a descoberta por
  tentativa e erro a partir da tabela.
- **Qual é o custo do encadeamento?** Um bloqueio por gravação, que serializa as evidências. O `bench/` mede o
  efeito com 10 e 100 usuários virtuais; o relatório só pode afirmar o que a sua medição mostrar.
- **O JWT resolve a autenticação do operador?** Resolve a **origem** do dado: o operador vem de uma assinatura
  verificada, não de um cabeçalho livre. Não prova que a pessoa certa usou a credencial.
- **Por que o emissor é simulado?** Para demonstrar a validação sem depender de um provedor de identidade. A
  aplicação não muda: em produção, troca-se a chave pública e o emissor nas propriedades `mp.jwt.verify.*`.
- **O que o trace com dois serviços prova?** Que o contexto de rastreamento (`traceparent`) atravessa a chamada
  entre dois serviços e que o `trace_id` gravado na evidência é o mesmo que aparece no Jaeger. Não prova
  autorização nem identidade: é correlação.
- **Por que a requisição sem token não aparece em `audit_log`?** Porque não chegou ao método anotado: não houve
  acesso a dado pessoal. A tentativa aparece no span HTTP, com status 401.
- **Por que POST e não GET?** O CPF na URL vai para o span do OpenTelemetry e para logs de servidor e proxy. No
  corpo, não. E `resource` guarda o **modelo** de rota, que identifica o recurso sem identificar a pessoa.
- **O `subject_ref` é anônimo?** Não. É dado pseudonimizado (LGPD, Art. 13, §4º): quem tem a chave refaz a ligação
  com o CPF. Serve para responder "quem acessou os dados deste titular?" sem guardar o CPF.
- **Por que a evidência é gravada em `REQUIRES_NEW`?** Para sobreviver a um rollback da transação de negócio: uma
  consulta que falhou no meio continua sendo um acesso ocorrido.
- **`lenient` ou `strict`?** `lenient` mantém o serviço no ar e aceita perder evidência; `strict` não entrega o
  dado sem registro e aceita ficar indisponível. A escolha depende do risco que a organização aceita.
