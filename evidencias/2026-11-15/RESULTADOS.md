# Resultados da execução — 15 nov. 2026

Execução dos Passos 0 a 5 de *TG-II v4: lista de pendências* nesta máquina. Todos os valores
abaixo saíram dos arquivos desta pasta (nada foi arredondado para melhor nem copiado de outra
máquina). Formato do relatório: vírgula decimal.

## Ambiente

| Item | Valor |
|---|---|
| Data | 15/11/2026, 11:00 a 11:35 (UTC−3) |
| CPU | Intel Core i3 M 350 @ 2,27 GHz, 4 núcleos lógicos |
| Memória | 7,6 GiB |
| SO | Linux 7.2.10-arch1-1 (Arch Linux) |
| Java | OpenJDK 21.0.13 (Temurin 21.0.13+9 LTS) |
| Maven | Apache Maven 3.10.0 (o pacote não traz `mvnw`; usado `mvn` do sistema) |
| PostgreSQL | 16.15 (imagem `postgres:16-alpine`), em contêiner |
| Jaeger | `jaegertracing/all-in-one:1.57`, em contêiner |
| k6 | v2.4.0 |
| Quarkus | 3.15.1 |

PostgreSQL e Jaeger rodaram em contêineres na mesma máquina que a aplicação e o k6 (confere com 5.4).

Arquivos: `ambiente_passo1.txt`, `bench-results/*_ambiente.txt`.

---

## Passo 1 · Testes automatizados

| Projeto | Resultado do Maven |
|---|---|
| Serviço de cidadãos | **Tests run: 91, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** (Finished at 2026-11-15T11:05:35-03:00) |
| Fachada | **Tests run: 17, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** (Finished at 2026-11-15T11:07:17-03:00) |
| Total | **108 de 108** |

### Falha encontrada e corrigida (registrar em 5.1, campo “Falhas, se houver”)

Na primeira execução (`passo1_cidadaos_mvn_test.log`) o resultado foi **89 de 91**: 2 erros, em
`FailureModeLenientTest.deveEntregarODadoESemEvidenciaQuandoOBancoFalha` e
`FailureModeStrictTest.deveNegarOAcessoQuandoAEvidenciaNaoPodeSerGravada` — justamente o ponto
de atenção previsto (`QuarkusMock.installMockForType`).

- **Causa:** o client proxy que o ArC (CDI do Quarkus 3.15.1) gera para `AuditLogService` é
  subclasse da classe do bean, e o mock instalado nele precisa ser desse tipo. O dublê
  `BancoIndisponivel` só implementava a interface `AuditEvidenceStore`, e o `arc$setMock`
  lançava `ClassCastException` (mensagem exibida: “is not a normal scoped CDI bean”).
- **Correção (só no dublê de teste, nenhuma asserção alterada):** `BancoIndisponivel` passou a
  estender `AuditLogService`, com `@Vetoed` para não virar um segundo bean no contêiner.
- Depois da correção: 3 de 3 nos dois testes (`passo1_failuremode_rerun.log`) e 91 de 91 na
  suíte completa (`passo1_cidadaos_mvn_test_final.log`).

Sugestão de texto para 5.1: os dois testes de modo de falha não rodaram na primeira execução por
um erro no dublê de teste, não no interceptador; corrigido o dublê, todos passaram. Assim, “sem
falhas nem testes ignorados” vale para a execução final, mas convém registrar o ocorrido.

### Campos

| Onde | O que escrever |
|---|---|
| Resumo / ABSTRACT | **91 de 91 e 17 de 17** |
| Quadro 7, Total | **Aprovados: 108 de 108** |
| Quadro 6, linha do `sub` | `AutenticacaoTest.deveUsarOSubQuandoOTokenNaoTemUpn`: **aprovado** (os 12 testes de `AutenticacaoTest` passaram); o smallrye-jwt caiu em `sub`, a frase de 4.1 se mantém |
| 5.4 e 6.1 (ConcurrencyTest) | **Aprovado** (`ConcurrencyTest`, 1 de 1, 3,7 s) |
| PropagacaoTest / ManualTest / ServicoIndisponivelTest | 9 de 9, 1 de 1, 1 de 1 — aprovados (502 confirmado pelo teste) |
| Figura 7 | Capturar o fim de `passo1_cidadaos_mvn_test_final.log` e `passo1_fachada_mvn_test.log` (Tests run, BUILD SUCCESS, Finished at) — **captura de tela a fazer por você** |

---

## Passo 2 · Roteiro com os dois serviços

`./test-audit.sh`: 14 verificações ✔, 0 ✘, código de saída 0 (`passo2_test-audit.txt`).
`./scripts/e2e-trace.sh fiscal_maria`: **RESULTADO: OK**, código 0 (`passo2_e2e-trace.txt`).
`./scripts/verify-chain.sh`: **RESULTADO: OK**, 3 registros (`passo2_verify-chain.txt`).

- **5.2.2, resultado:** **um único trace** com os dois serviços (`auditgov-facade` e `auditgov`),
  spans `POST /api/v1/portal/consulta`, `POST /api/v1/cidadaos/consulta` e `auditgov.evidence`.
- **Modo de propagação:** **automático** (instrumentação do cliente REST;
  `facade.trace.manual-propagation=false`).
- **CPF no trace:** `grep -c "12345678900"` = **0**.

### Quadro 9 (trace_id `b4578b69aa7e96fbcb4eb41c7d2d6298`)

| Campo | PostgreSQL (`audit_log`) | Jaeger (span `auditgov.evidence`) | Confere |
|---|---|---|---|
| Trace ID | b4578b69aa7e96fbcb4eb41c7d2d6298 | b4578b69aa7e96fbcb4eb41c7d2d6298 | Sim |
| Span ID | b3a38013a0baa459 | b3a38013a0baa459 | Sim |
| Operador | fiscal_maria | audit.operator = fiscal_maria | Sim |
| Ação | READ_CITIZEN_DATA | audit.action = READ_CITIZEN_DATA | Sim |
| Recurso | POST /api/v1/cidadaos/consulta | audit.resource = POST /api/v1/cidadaos/consulta | Sim |
| Titular (subject_ref) | 9b5db533a5a6b78b95d51c20c68686a3c0e762df70775c6ebb90f16b162d080c | audit.subject_ref = (idem) | Sim |
| HMAC do payload | a9fabdec8101e11c50656f1300ace3de5702f6846f3ffa0b68c3bb287a95aa44 | audit.payload.hmac = (idem) | Sim |
| Serviços no trace | — | auditgov, auditgov-facade | Sim |

Outros dados da linha: `seq` 3, `event_timestamp` 2026-11-15 14:09:43.287056+00,
`record_hash` 4a94b8d3…7893; tag `audit.persisted=true`. Saída completa em `passo2_quadro9.txt`.

### Quadro 8 (CT01 a CT10)

Todos os testes automatizados correspondentes passaram (Passo 1) e o roteiro do Passo 2 passou:
**CT01 a CT10 = Aprovado**. (CT10: pelos testes `FailureModeLenientTest` e
`FailureModeStrictTest`, após a correção do dublê; o item 7 manual do Apêndice A não foi executado.)

Itens 1 a 7 do Apêndice A: cobertos pelo `test-audit.sh` nos pontos principais; a passagem
manual item a item com curl/psql **não** foi feita aqui.

### Objetivos (Seção 4 do documento de pendências)

- OE01: trace único com os dois serviços → **Concluído**.
- OE04: HMAC, trace_id e span_id do Jaeger idênticos aos do banco → **Concluído**.
- OE02: Concluído (pelo desenho). OE03: Parcial (ConcurrencyTest passou).
- **N = 3 objetivos específicos concluídos** (OE01, OE02, OE04).

### Capturas (a fazer por você)

- Figura 5: http://localhost:16686/trace/b4578b69aa7e96fbcb4eb41c7d2d6298 (o Jaeger guarda o trace
  em memória: ele se perdeu quando os contêineres foram recriados no Passo 3; rode de novo o
  `e2e-trace.sh` e capture o trace novo).
- Figura 6: saída de `passo2_e2e-trace.txt` e `passo2_test-audit.txt` (com data e hora).

---

## Passo 3 · Desempenho (k6)

Aplicação empacotada (`target/quarkus-app/quarkus-run.jar`), banco recriado antes de cada modo,
20 s de aquecimento + 2 níveis × 3 repetições × 60 s. Execução 14:11–14:32 UTC.

### Tabela 2 — mediana (mín. a máx.) das 3 repetições

| Modo | VUs | p50 (ms) | p95 (ms) | p99 (ms) | Vazão (req/s) | Erros (%) |
|---|---|---|---|---|---|---|
| sem_auditoria | 10 | 4,29 | 12,77 (12,41 a 27,92) | 20,37 | 1694,77 (933,15 a 1739,32) | 0 |
| sem_auditoria | 100 | 42,19 | 133,69 (128,19 a 145,94) | 209,62 | 1837,69 (1667,80 a 1850,59) | 0 |
| com_auditoria_always_on | 10 | 111,45 | 124,94 (123,29 a 161,87) | 140,35 | 89,55 (89,04 a 96,32) | 0 |
| com_auditoria_always_on | 100 | 1098,37 | 1160,09 (1157,28 a 1179,58) | 1207,05 | 90,86 (88,79 a 91,14) | 0 |
| com_auditoria_amostragem_10pct | 10 | 109,75 | 120,77 (118,27 a 132,97) | 132,52 | 90,49 (88,81 a 92,08) | 0 |
| com_auditoria_amostragem_10pct | 100 | 1056,38 | 1118,72 (1109,97 a 1157,80) | 1155,87 | 94,49 (91,56 a 94,63) | 0 |

Fonte: `bench-results/*.csv`, `passo3_medianas.txt`. A 1ª repetição de sem_auditoria a 10 VUs foi
mais lenta (933 req/s; JIT ainda aquecendo), por isso o intervalo largo.

**Atenção ao comando do documento de pendências:** em locale pt_BR, o `sort -n` trata o ponto
como separador de milhar e ordena errado (min > máx em algumas células, mediana errada). Os
valores acima foram calculados com `LC_ALL=C` e `sort -g`. Use estes, não os de uma execução do
comando original sem `LC_ALL=C`.

### 5.4 — números

- Acréscimo no p95 (always_on − sem_auditoria): **+112,17 ms (10 VUs)** e **+1026,40 ms (100 VUs)**.
- Variação da vazão: **−94,7% (10 VUs)** e **−95,1% (100 VUs)**.
- **O teto da serialização apareceu:** com auditoria a vazão fica em ~90 req/s nos dois níveis
  (não cresce de 10 para 100 VUs), enquanto o p95 cresce ~9× (125 → 1160 ms). Sem auditoria a
  vazão vai de 1695 para 1838 req/s. Cerca de 11 ms por gravação em fila única
  (1000 ms ÷ ~90) nesta máquina (notebook antigo, i3 M 350).
- A amostragem de 10% dos traces praticamente não muda nada: o custo é a gravação serializada no
  PostgreSQL, não a exportação dos spans.

### 5.4 — cadeia e evidências

| Modo | Válida antes | Válida depois | Registros depois | Evidências novas | Requisições enviadas |
|---|---|---|---|---|---|
| sem_auditoria | true (0) | true | 0 | 0 | 590 258 |
| com_auditoria_always_on | true (1) | true | 34 233 | 34 232 | 34 232 |
| com_auditoria_amostragem_10pct | true (1) | true | 35 019 | 35 018 | 35 018 |

Evidências novas = requisições nos dois modos com auditoria: nenhuma perda de evidência.

### Resumo / ABSTRACT / Quadro 3

- Resumo: “com **10 e 100** usuários virtuais, a auditoria acrescentou **112 ms e 1026 ms** ao
  p95 e reduziu a vazão em **94,7% e 95,1%**”. Reconte as palavras (máx. 250).
- **RNF06: Não atendido** (ou Parcial, a combinar com o orientador): o impacto é grande — a vazão
  cai ~95% por causa da serialização das gravações. Releia em 7.1 “viável como base”.

---

## Passo 4 · Armazenamento por evento

- `\d audit_log` real (`passo4_schema_audit_log.txt`): mesmos tipos e os mesmos 4 índices
  secundários + PK + único em `seq`, **mas ordem de coluna diferente**: o Hibernate pôs
  `event_timestamp` depois de `subject_ref` (ordena pelo atributo Java `timestamp`), e o SQL o
  tinha como 3ª coluna. **O `bench/measure-storage.sql` foi ajustado** para a ordem real
  (dizer isso no texto de 6.2).
- O `VACUUM (ANALYZE)` falhou na 1ª tentativa (`/dev/shm` de 64 MB do contêiner não comporta o
  vacuum paralelo; `passo4_measure-storage_tentativa1_shm.txt`). Refeito com
  `max_parallel_maintenance_workers=0` (só nesta sessão do psql), concluído sem erro.

| Medida | Valor |
|---|---|
| bytes_tabela | **455 114 752** (434,0 MiB) |
| bytes_indices | **311 967 744** (297,5 MiB) |
| bytes_total | **767 238 144** (731,7 MiB) |
| bytes por evento | **767** (767,2) |
| C (1 milhão de eventos/mês, US$ 0,115/GB-mês) | **US$ 0,08** |
| 12 × C | **US$ 0,99** |

Apêndice A, item 9: os três valores em bytes acima. Tabela 4 e 6.2: X = 434,0 MiB,
Y = 297,5 MiB, Z = 731,7 MiB, B = 767 bytes, C = US$ 0,08 (12 × C = US$ 0,99).

---

## Passo 5 · OpenAPI

`./scripts/export-openapi.sh` gravou `docs/openapi.yaml` (238 linhas) e `docs/openapi.json`, gerados
pela aplicação em `quarkus:dev`, sem edição. Falta a **captura do Swagger UI**
(http://localhost:8080/q/swagger-ui) — a fazer por você.

---

## O que ainda depende de você

1. Capturas: Figuras 5, 6 e 7 e Swagger UI (Anexo A).
2. Preencher o .docx com os valores acima e tirar os realces.
3. Passo 6 (banca, declaração de IA, link do repositório, referências, preços AWS).
4. Antes de publicar o repositório: `application.properties` tem seu e-mail no contato do OpenAPI
   (e `docs/openapi.*` também), e `src/test/resources/jwt/` tem a chave privada de laboratório.
