package br.gov.auditgov.interceptor;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.AuditLogService;
import jakarta.enterprise.inject.Vetoed;

/**
 * Dublê de teste que simula o PostgreSQL indisponível no momento de gravar a
 * evidência. Instalado com QuarkusMock.installMockForType(..., AuditEvidenceStore.class).
 *
 * Estende AuditLogService porque o client proxy que o ArC gera para o bean é uma
 * subclasse de AuditLogService, e o mock instalado nele precisa ser desse tipo
 * (com um dublê que só implementava a interface, o Quarkus 3.15.1 lançava
 * ClassCastException em arc$setMock). O @Vetoed impede que esta classe vire um
 * segundo bean concorrente no contêiner CDI (o @ApplicationScoped é herdado).
 */
@Vetoed
public class BancoIndisponivel extends AuditLogService {

    @Override
    public void register(AuditLog auditLog) {
        throw new IllegalStateException("PostgreSQL indisponível (simulado no teste)");
    }
}
