-- ============================================================================
-- AuditGov - separacao de papeis no PostgreSQL (endurecimento recomendado em producao)
-- ----------------------------------------------------------------------------
-- auditgov_owner : dono do esquema; roda migracoes; NAO e usado pela aplicacao.
-- auditgov_app   : usado pela aplicacao; so pode inserir e ler audit_log.
--
-- O gatilho append-only (src/main/resources/db/audit-append-only.sql) bloqueia
-- UPDATE/DELETE/TRUNCATE para todos, mas o dono da tabela ainda pode remove-lo.
-- Este script completa a protecao: a aplicacao passa a rodar com um papel que nem
-- sequer tem permissao para UPDATE, DELETE, TRUNCATE ou para alterar a tabela.
--
-- Como usar:
--   1. Execute este script como superusuario ou dono, DEPOIS de a tabela audit_log existir
--      (a aplicacao precisa ter subido uma vez com o dono para criar a tabela).
--   2. Aponte a aplicacao para o papel auditgov_app e desligue a geracao automatica do
--      esquema, que exige privilegios de dono:
--        quarkus.datasource.username=auditgov_app
--        quarkus.datasource.password=<senha>
--        quarkus.hibernate-orm.database.generation=none
--        auditgov.append-only.enabled=false   (o gatilho ja foi instalado pelo dono)
--
-- A cadeia de hash NAO exige privilegio extra: a gravacao usa INSERT, a leitura da cabeca da cadeia usa
-- SELECT e o bloqueio usa pg_advisory_xact_lock, funcao executavel por qualquer papel (verificado em
-- PostgreSQL 16: o papel so com INSERT/SELECT toma o bloqueio, le a cabeca e recebe "permission denied"
-- em UPDATE e DELETE). Com quarkus.hibernate-orm.database.generation=none, o dono precisa criar o esquema
-- completo, incluindo as colunas seq, prev_hash e record_hash e a restricao de unicidade de seq.
--
-- Substitua a senha antes de usar fora do laboratorio.
-- ============================================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'auditgov_app') THEN
        CREATE ROLE auditgov_app LOGIN PASSWORD 'trocar-esta-senha';
    END IF;
END
$$;

REVOKE ALL ON audit_log FROM auditgov_app;
GRANT INSERT, SELECT ON audit_log TO auditgov_app;
-- UPDATE, DELETE e TRUNCATE nao sao concedidos: qualquer tentativa pela
-- aplicacao falha com "permission denied for table audit_log".
