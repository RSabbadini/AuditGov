package br.gov.auditgov.interceptor;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Recurso usado só nos testes. Exige autenticação, mas não tem {@code @Auditable}: um operador
 * autenticado que o chama não deixa evidência (ver {@link MetodoNaoAnotadoTest}).
 */
@Path("/teste/sem-anotacao")
public class EndpointSemAnotacao {

    @GET
    @Authenticated
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        return "pong";
    }
}
