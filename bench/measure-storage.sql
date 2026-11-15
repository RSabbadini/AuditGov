-- ============================================================================
-- AuditGov - medicao do custo de armazenamento por evento de auditoria
-- ----------------------------------------------------------------------------
-- Cria uma tabela com o mesmo formato da entidade AuditLog desta versao: inclui
-- subject_ref, a posicao na cadeia (seq, com restricao de unicidade) e os dois hashes
-- da cadeia (prev_hash e record_hash), alem dos quatro indices secundarios. Insere 1
-- milhao de eventos SINTETICOS e informa o tamanho em disco (linhas + indices). Apenas o
-- TAMANHO importa; os valores sao ficticios.
--
-- Cada evento recebe um subject_ref distinto: e o pior caso para o indice (na pratica o
-- mesmo titular costuma aparecer em varios eventos, e o indice fica menor). Os hashes sao
-- sequencias hexadecimais de 64 caracteres sem estrutura, como os HMAC-SHA-256 reais.
--
-- A ordem das colunas e a que o Hibernate gerou para audit_log no Quarkus 3.15.1, conferida
-- com \d audit_log em 15 nov. 2026 (event_timestamp fica depois de subject_ref, porque o
-- Hibernate ordena pelo nome do atributo Java, "timestamp", e nao pelo nome da coluna). A
-- ordem influencia o tamanho (alinhamento): confira de novo se a entidade mudar.
--
-- Uso: psql -U <usuario> -d <banco_descartavel> -f bench/measure-storage.sql
-- Mexe apenas na tabela audit_log_medicao (nao toca em audit_log), mas ocupa
-- algumas centenas de MB: use um banco descartavel.
--
-- Alternativa, na tabela REAL depois de uma carga (dados reais, com subject_ref repetido):
--   SELECT count(*) AS eventos,
--          pg_total_relation_size('audit_log') AS bytes_total,
--          pg_total_relation_size('audit_log') / NULLIF(count(*), 0) AS bytes_por_evento
--   FROM audit_log;
-- ============================================================================
SELECT version();

DROP TABLE IF EXISTS audit_log_medicao;
CREATE TABLE audit_log_medicao (
    id uuid PRIMARY KEY,
    action varchar(80) NOT NULL,
    operator_id varchar(120) NOT NULL,
    payload_hash varchar(64) NOT NULL,
    prev_hash varchar(64) NOT NULL,
    record_hash varchar(64) NOT NULL,
    resource varchar(500) NOT NULL,
    seq bigint NOT NULL,
    span_id varchar(16) NOT NULL,
    subject_ref varchar(64),
    event_timestamp timestamptz(6) NOT NULL,
    trace_id varchar(32) NOT NULL,
    CONSTRAINT uk_audit_medicao_seq UNIQUE (seq));
CREATE INDEX ON audit_log_medicao (trace_id);
CREATE INDEX ON audit_log_medicao (operator_id);
CREATE INDEX ON audit_log_medicao (event_timestamp);
CREATE INDEX ON audit_log_medicao (subject_ref);
INSERT INTO audit_log_medicao (id, action, event_timestamp, operator_id, payload_hash,
                               prev_hash, record_hash, resource, seq, span_id,
                               subject_ref, trace_id)
SELECT gen_random_uuid(),
       CASE WHEN g % 10 = 0
            THEN 'READ_CITIZEN_DATA_FAILED'
            ELSE 'READ_CITIZEN_DATA' END,
       now() - (g || ' seconds')::interval,
       'fiscal_' || (g % 500),
       md5(g::text) || md5((g + 1)::text),
       -- prev_hash: o record_hash do evento anterior (o primeiro usa 64 zeros, como o genesis).
       CASE WHEN g = 1 THEN repeat('0', 64)
            ELSE md5(((g - 1) * 17)::text) || md5(((g - 1) * 17 + 1)::text) END,
       md5((g * 17)::text) || md5((g * 17 + 1)::text),
       'POST /api/v1/cidadaos/consulta',
       g,
       substr(md5((g * 7)::text), 1, 16),
       md5((g * 11)::text) || md5((g * 11 + 1)::text),
       md5((g * 13)::text)
FROM generate_series(1, 1000000) AS g;
VACUUM (ANALYZE) audit_log_medicao;
SELECT count(*)                                    AS eventos,
       pg_relation_size('audit_log_medicao')       AS bytes_tabela,
       pg_indexes_size('audit_log_medicao')        AS bytes_indices,
       pg_total_relation_size('audit_log_medicao') AS bytes_total,
       round(pg_total_relation_size('audit_log_medicao') / count(*)::numeric, 1) AS bytes_por_evento
FROM audit_log_medicao;
