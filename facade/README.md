# AuditGov – fachada (segundo serviço)

Projeto Maven **independente**, ao lado do serviço de cidadãos (o `pom.xml` da raiz). Existe para um único
fim: **demonstrar a propagação do contexto de rastreamento entre dois serviços**.

```
cliente ──► fachada (auditgov-facade, :8081) ──► serviço de cidadãos (auditgov, :8080) ──► audit_log
            abre um span                         continua o MESMO trace                  grava o mesmo trace_id
                         └────────────── Jaeger: UM trace com spans dos dois serviços ──────────────┘
```

Uma chamada a `POST http://localhost:8081/api/v1/portal/consulta` deve produzir:

1. **um trace só no Jaeger**, com spans dos serviços `auditgov-facade` e `auditgov`;
2. **uma evidência em `audit_log`** (gravada pelo serviço de cidadãos) cujo `trace_id` é o mesmo desse trace;
3. o **operador** gravado é o do **token JWT** (`upn`), que a fachada repassa e o serviço de cidadãos valida.

O cabeçalho que liga os dois é o W3C Trace Context `traceparent`. Ele serve para **correlação**: não
autentica nem autoriza ninguém e não leva dado pessoal.

## O que a fachada faz e não faz

| Faz | Não faz |
|---|---|
| Recebe `{"cpf": "..."}` no corpo e o repassa ao serviço de cidadãos (`POST /api/v1/cidadaos/consulta`). | **Não valida o token.** Quem o valida (e responde 401) é o serviço de cidadãos, que guarda o dado e grava a evidência. |
| Repassa o `Authorization` recebido, sem alterar. | Não repassa o `X-User-ID` (a identidade vem só do token). |
| Devolve o JSON do serviço de cidadãos quando ele responde 200. | Não grava nem escreve em log o CPF ou o dado devolvido. |
| Repassa **só o status** dos erros do serviço de cidadãos (401, 403, 404, 400, 503…), nunca o corpo. | Não tem auditoria própria: a evidência de acesso a dado é a do serviço de cidadãos. |
| Responde **502** se o serviço de cidadãos não responde (conexão recusada, tempo esgotado). | Não tenta de novo (sem *retry*), para não duplicar acesso a dado. |
| Responde **400** se o CPF faltar, sem chamar o serviço de cidadãos. | |

## Como executar

Três terminais. O banco e o Jaeger sobem uma vez, pela raiz do projeto:

```bash
# 1) Raiz do projeto: PostgreSQL + Jaeger
docker compose up -d

# 2) Serviço de cidadãos, porta 8080 (raiz do projeto)
./mvnw quarkus:dev

# 3) Fachada, porta 8081
cd facade
../mvnw quarkus:dev -Ddebug=false
```

`-Ddebug=false` evita que a segunda instância do modo dev tente abrir a mesma porta de depuração (5005) do
serviço de cidadãos. O `../mvnw` usa o Maven Wrapper da raiz; sem ele, use `mvn` (veja a nota no README da raiz).

Chamada de exemplo (o token vem de `scripts/mint-token.sh`, o emissor de **laboratório**):

```bash
TOKEN=$(./scripts/mint-token.sh fiscal_maria)
curl -i -X POST http://localhost:8081/api/v1/portal/consulta \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cpf":"12345678900"}'
```

## Prova com os dois serviços de verdade

```bash
./scripts/e2e-trace.sh fiscal_maria
```

O script escolhe um `trace_id`, chama a fachada com ele (`traceparent`) e confere, **com esse mesmo
`trace_id`**: (1) a resposta HTTP 200 da fachada; (2) a linha em `audit_log` com o operador do token; (3) o
trace no Jaeger com os dois serviços. O código de saída é 0 só se as três conferências passarem.

Para o relatório (Figura do trace), abra a URL que o script imprime no final (`…/trace/<trace_id>`) e capture
a tela do Jaeger mostrando **os dois serviços no mesmo trace**. A figura e a saída do script só valem como
evidência se foram geradas na sua máquina, na sua execução.

## Testes da fachada

```bash
cd facade
../mvnw test
```

Não precisam de banco nem de Jaeger. O "serviço de cidadãos" dos testes é um servidor HTTP falso do próprio
JDK (`CidadaosFalso`, porta fixa 18089) que **grava o que recebeu**; a fachada de teste sobe na porta 8091.

| Classe | O que prova |
|---|---|
| `PropagacaoTest` | O serviço de cidadãos recebe um `traceparent` com o **mesmo trace-id** da requisição de entrada e com outro span-id (o pai é um span da fachada); o `Authorization` é repassado sem alteração; o CPF vai só no corpo; o `X-User-ID` não é repassado; o JSON do serviço é devolvido; 401, 404 (sem o corpo de erro) e 503 são repassados; sem CPF, 400 e nenhuma chamada. |
| `PropagacaoManualTest` | Com `facade.trace.manual-propagation=true`, a fachada monta o `traceparent` a partir do span atual (contingência, veja abaixo). |
| `ServicoIndisponivelTest` | Com o serviço de cidadãos fora do ar, a fachada responde 502. |
| `TraceparentHeaderTest` | Formato do `traceparent` (lógica pura, sem Quarkus). |

**Se `PropagacaoTest` falhar** com *"o serviço de cidadãos deveria ter recebido o traceparent"*, a
instrumentação automática do cliente REST não injetou o cabeçalho nesta versão do Quarkus. Nesse caso confira
`PropagacaoManualTest`: se ele passar, deixe `facade.trace.manual-propagation=true` em
`src/main/resources/application.properties` e rode o `scripts/e2e-trace.sh` de novo.

## Configuração (`src/main/resources/application.properties`)

| Propriedade | Padrão | Efeito |
|---|---|---|
| `quarkus.http.port` | `8081` | Porta da fachada. |
| `quarkus.rest-client.citizen.url` | `http://localhost:8080` | Endereço do serviço de cidadãos. |
| `quarkus.rest-client.citizen.connect-timeout` / `read-timeout` | `2000` / `5000` ms | Tempos limite da chamada. |
| `facade.trace.manual-propagation` | `false` | `true`: a fachada monta o `traceparent` a partir do span atual (contingência). |
| `quarkus.otel.*` | como no serviço de cidadãos | Exportação OTLP/HTTP para o Jaeger (4318) e propagadores `tracecontext,baggage`. **Devem ser os mesmos nos dois serviços.** |

## Limites conhecidos

- **O repasse do token é uma simplificação.** A fachada encaminha o token do chamador ao serviço de cidadãos.
  Em produção, considere troca de token (com *audience* restrita) em vez de repassar a credencial original.
- **O token prova uma credencial válida, não a pessoa.** Quem tiver o token (inclusive emprestado) é aceito.
- **A fachada não valida o token.** Sem token, ela chama o serviço de cidadãos mesmo assim e repassa o 401 dele.
  Uma fachada exposta à internet deveria validar também (defesa em profundidade); aqui isso foi deixado de fora
  para manter o foco na propagação.
- **O `traceparent` é correlação, não prova de origem.** Qualquer cliente pode enviar um `traceparent`; o
  serviço de cidadãos o aceita como contexto. O que identifica o operador é o JWT.
- **Spans dependem do Jaeger e da amostragem.** Com o Jaeger fora do ar, os spans se perdem, mas a evidência
  em `audit_log` continua sendo gravada. Com amostragem menor que 100%, o trace pode não existir no Jaeger.
- **Requisições recusadas (401) não geram evidência de acesso a dado**, porque não chegam ao método anotado do
  serviço de cidadãos; a tentativa aparece só nos spans HTTP.
- **Projeto de demonstração.** Sem TLS, sem limite de taxa, sem *retry*, sem *circuit breaker*.
