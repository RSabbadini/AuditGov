package br.gov.auditgov.interceptor;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.AuditEvidenceStore;

/**
 * Dublê de teste que simula o PostgreSQL indisponível no momento de gravar a
 * evidência. Instalado com QuarkusMock.installMockForType(..., AuditEvidenceStore.class).
 *
 * Implementa a interface (e não estende AuditLogService) para não virar, por
 * herança de escopo, um segundo bean concorrente no contêiner CDI.
 */
public class BancoIndisponivel implements AuditEvidenceStore {

    @Override
    public void register(AuditLog auditLog) {
        throw new IllegalStateException("PostgreSQL indisponível (simulado no teste)");
    }
}
