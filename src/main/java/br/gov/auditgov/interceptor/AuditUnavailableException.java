package br.gov.auditgov.interceptor;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

/**
 * Lançada no modo estrito ({@code auditgov.failure-mode=strict}) quando a
 * evidência de auditoria não pôde ser gravada. Resulta em HTTP 503 sem corpo,
 * de modo que o dado consultado não é entregue ao cliente.
 */
public class AuditUnavailableException extends WebApplicationException {

    private static final long serialVersionUID = 1L;

    public AuditUnavailableException() {
        super("Registro de auditoria indisponível; o acesso não foi concedido.",
                Response.Status.SERVICE_UNAVAILABLE);
    }
}
