# Medição de desempenho do AuditGov

Este roteiro mede o custo da auditoria sobre a latência e a vazão do endpoint
`POST /api/v1/cidadaos/consulta`. Os números **não existem ainda**: ninguém os mediu. Rode o roteiro na sua
máquina, guarde os CSV e use os valores obtidos no relatório (Seção 5.4). Não use valores de outro lugar.

## O que é medido

| Modo | Como subir a aplicação | O que representa |
|---|---|---|
| `sem_auditoria` | `-Dauditgov.enabled=false` | O interceptador só repassa a chamada (linha de base). |
| `com_auditoria_always_on` | padrão | Evidência no PostgreSQL (com encadeamento de hash) + span no Jaeger, 100% dos acessos rastreados. |
| `com_auditoria_amostragem_10pct` | `-Dquarkus.otel.traces.sampler=parentbased_traceidratio -Dquarkus.otel.traces.sampler.arg=0.1` | Evidência completa no PostgreSQL, mas só ~10% dos traces exportados. |

Para cada modo, o script mede 10 e 100 usuários virtuais, com 3 repetições de 60 s após um aquecimento
de 20 s, e registra **p50, p95 e p99** da latência (ms), a **vazão** (req/s), o percentual de erros e o total
de requisições enviadas.

**A validação do JWT está em todos os modos.** Cada usuário virtual envia um token próprio
(`Authorization: Bearer ...`, operadores `bench_1` a `bench_N`) e a aplicação o valida sempre, com ou sem
auditoria. Assim, a diferença entre os modos é o custo do **trabalho de auditoria**, não o da autenticação.

**Limite da linha de base:** em `sem_auditoria` a anotação continua presente e o interceptador ainda é
invocado (só não faz nada). A diferença medida é, portanto, o custo do trabalho de auditoria (HMAC, cadeia
de hash, span, gravação), e não o custo de existir um interceptador. Diga isso no relatório.

## O que esperar da cadeia de hash (leia antes de interpretar a vazão)

Para que o encadeamento não tenha buracos nem bifurcações, cada gravação de evidência toma um bloqueio
consultivo do PostgreSQL (`pg_advisory_xact_lock`) e só o solta ao confirmar. **As gravações de evidência
são, portanto, serializadas**: com muitos usuários simultâneos, a vazão com auditoria tem um teto imposto
por esse bloqueio, e a latência dos acessos auditados cresce com a fila. É um custo real do desenho, e a
medição com 100 usuários virtuais existe, em parte, para mostrá-lo. Relate o que o seu ambiente mostrou;
não extrapole para outro banco, outro disco ou outro nível de concorrência.

## Pré-requisitos

- `docker compose up -d` (PostgreSQL e Jaeger no ar).
- [k6](https://k6.io/docs/get-started/installation/), `jq`, `curl` e `openssl` instalados (Linux, macOS ou WSL).
- Aplicação **empacotada** (o modo `quarkus:dev` tem sobrecarga própria e distorce a medição):

```bash
./mvnw package -DskipTests
export AUDITGOV_HMAC_KEY='uma-chave-qualquer-com-pelo-menos-16-bytes'   # obrigatória fora do modo dev
```

Use **a mesma chave** em todas as execuções que compartilham o mesmo banco: a cadeia de hash é calculada
com uma subchave derivada dela, e trocar a chave faz a verificação acusar divergência nos registros antigos.

## Passo a passo

Faça um modo de cada vez, **com o banco do zero** e reiniciando a aplicação entre eles. Começar cada modo
com a tabela vazia evita que o segundo modo meça uma tabela maior que a do primeiro.

```bash
# Modo 1: sem auditoria
docker compose down -v && docker compose up -d      # banco e Jaeger do zero (espere ficar saudável)
java -Dauditgov.enabled=false -jar target/quarkus-app/quarkus-run.jar
#   em outro terminal:
./bench/run-benchmark.sh sem_auditoria
#   encerre a aplicação (Ctrl+C)

# Modo 2: com auditoria, 100% dos traces
docker compose down -v && docker compose up -d
java -jar target/quarkus-app/quarkus-run.jar
./bench/run-benchmark.sh com_auditoria_always_on

# Modo 3: com auditoria, ~10% dos traces
docker compose down -v && docker compose up -d
java -Dquarkus.otel.traces.sampler=parentbased_traceidratio \
     -Dquarkus.otel.traces.sampler.arg=0.1 \
     -jar target/quarkus-app/quarkus-run.jar
./bench/run-benchmark.sh com_auditoria_amostragem_10pct
```

Cada modo leva cerca de 7 minutos (aquecimento de 20 s + 2 níveis × 3 repetições × 60 s).

## O que o script faz e grava

`run-benchmark.sh` emite os tokens de laboratório (`scripts/mint-token.sh`), confirma que a aplicação
responde 200, **verifica a cadeia antes e depois da carga** (endpoint `GET /api/v1/auditoria/cadeia/verificacao`,
com um token de papel `auditor`) e compara quantas evidências novas entraram na cadeia com quantas
requisições o k6 enviou. Em `bench/results/` (ignorado pelo git):

| Arquivo | Conteúdo |
|---|---|
| `<rotulo>.csv` | Uma linha por repetição: `rotulo,vus,repeticao,p50_ms,p95_ms,p99_ms,rps,erro_pct,reqs`. |
| `<rotulo>_cadeia.csv` | Verificação da cadeia antes e depois: `valida`, `registros` e `segundos_verificacao`. |
| `<rotulo>_ambiente.txt` | Data, SO, CPU, memória, Java, k6 e PostgreSQL, para a descrição do ambiente no relatório. |
| `<rotulo>_vus<N>_rep<R>.json` | Resumo bruto de cada repetição. |
| `<rotulo>_tokens.txt` | Tokens de **laboratório** (credenciais do emissor de desenvolvimento). Apague ao terminar. |

Leitura da conferência de evidências: em modo **com auditoria**, `evidências novas` deve ser igual a
`requisições enviadas` quando todas as respostas foram 200. Se for menor, houve resposta recusada (veja
`erro_pct`) ou **perda de evidência** (no modo `lenient` a falha de gravação não vira erro para o cliente);
investigue antes de usar os números. Em `sem_auditoria`, `evidências novas` deve ser 0. Se a verificação
final acusar `valida=false`, o script termina com código 3: isso é um achado, não um detalhe.

## Como relatar

- Use `<rotulo>_ambiente.txt` para informar a máquina (CPU, memória), o sistema operacional, a versão do
  Java, do k6 e do PostgreSQL, e diga que PostgreSQL e Jaeger rodaram em contêineres na mesma máquina que a
  aplicação e o k6.
- Para cada modo e nível de carga, apresente a **mediana das 3 repetições** de p50, p95 e p99 e da vazão, e
  mostre a variação entre repetições (mínimo e máximo). Com 3 repetições não há base para teste estatístico.
- Informe também o resultado da verificação da cadeia (`valida`, número de registros e tempo) e a conferência
  de evidências (novas × enviadas).
- Escreva a conclusão só com o que os números mostram, nesse ambiente. Não generalize para produção.
- A comparação entre `always_on` e amostragem responde apenas ao custo de exportar traces; a evidência no
  PostgreSQL (e a cadeia) é gravada nos dois casos.

## Custo de armazenamento por evento

`bench/measure-storage.sql` cria uma tabela com o formato de `audit_log` desta versão (inclui `subject_ref`,
`seq`, `prev_hash`, `record_hash` e os índices), insere 1 milhão de eventos **sintéticos** e informa o tamanho
em disco das linhas e dos índices. Use um banco descartável (a tabela de medição ocupa algumas centenas de MB):

```bash
psql -U audit_user -d <banco_descartavel> -f bench/measure-storage.sql
```

Como o esquema ganhou três colunas e um índice único, **o número de versões anteriores não vale mais**:
meça de novo. O resultado vale para a sua versão do PostgreSQL e para esse formato de linha. A consulta
alternativa no cabeçalho do arquivo mede a tabela real depois de uma carga. O tamanho dos traces armazenados
no Jaeger não é medido por este roteiro.

## Teste de contexto sob concorrência

O teste automatizado `ConcurrencyTest` envia 100 requisições simultâneas, cada uma com um token (operador) e
um `traceparent` próprios, e confere que nenhuma evidência mistura o contexto de outra, que as 100
evidências têm posições `seq` distintas e consecutivas e que a cadeia continua íntegra. Ele roda com
`./mvnw test -Dtest=ConcurrencyTest` e verifica integridade, não desempenho.
