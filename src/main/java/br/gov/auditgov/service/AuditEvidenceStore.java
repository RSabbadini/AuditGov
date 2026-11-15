package br.gov.auditgov.service;

import br.gov.auditgov.domain.AuditLog;

/**
 * Porta de gravação das evidências de auditoria.
 *
 * O interceptador depende desta interface, e não da classe concreta, para que os
 * testes possam simular a indisponibilidade do banco sem estender um bean CDI.
 */
public interface AuditEvidenceStore {

    /**
     * Grava a evidência em transação própria. Lança exceção se não for possível
     * gravar; quem chama decide o que fazer conforme auditgov.failure-mode.
     */
    void register(AuditLog auditLog);
}
