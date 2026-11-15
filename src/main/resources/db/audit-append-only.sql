-- ============================================================================
-- AuditGov - audit_log somente-anexo (append-only) no proprio PostgreSQL
-- ----------------------------------------------------------------------------
-- Rejeita UPDATE, DELETE e TRUNCATE sobre audit_log para qualquer usuario que
-- nao desative o gatilho de proposito. O script e idempotente.
--
-- Este arquivo e a fonte unica: a classe AuditSchemaGuard le este mesmo arquivo
-- no inicio da aplicacao e executa cada comando separadamente. Por isso, deixe
-- UMA LINHA EM BRANCO entre comandos e NENHUMA linha em branco dentro de um comando.
-- Tambem pode ser aplicado manualmente: psql -d <banco> -f audit-append-only.sql
--
-- Limite conhecido: o dono da tabela e o superusuario ainda podem desabilitar o
-- gatilho. Para fechar essa lacuna, use db/audit-app-role.sql (papel da aplicacao
-- sem UPDATE/DELETE) e mantenha o dono da tabela fora da aplicacao.
-- ============================================================================

CREATE OR REPLACE FUNCTION audit_log_block_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_log e append-only: operacao % bloqueada', TG_OP
        USING ERRCODE = '23001';
END;
$$;

DROP TRIGGER IF EXISTS audit_log_no_update_delete ON audit_log;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION audit_log_block_mutation();

DROP TRIGGER IF EXISTS audit_log_no_truncate ON audit_log;

CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit_log_block_mutation();
