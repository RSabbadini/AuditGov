-- Executado pelo PostgreSQL na PRIMEIRA inicialização do volume (docker-entrypoint-initdb.d).
-- Cria o banco usado pelos testes automatizados, separado do banco de demonstração.
CREATE DATABASE auditgov_test OWNER audit_user;
