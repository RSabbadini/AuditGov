package br.gov.auditgov.facade;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Cliente REST do serviço de cidadãos (o AuditGov principal). O endereço vem da configuração
 * {@code quarkus.rest-client.citizen.url}.
 *
 * O Authorization é repassado de forma EXPLÍCITA, como parâmetro: assim o que a fachada
 * encaminha fica visível no código e é conferido pelos testes. Quem VALIDA o token é o serviço
 * de cidadãos, que é quem guarda o dado e grava a evidência.
 *
 * O parâmetro {@code traceparent} fica nulo no modo padrão: o cabeçalho é então injetado pela
 * instrumentação OpenTelemetry do Quarkus. Só no modo manual
 * ({@code facade.trace.manual-propagation=true}) a fachada o preenche, por contingência.
 * Parâmetro de cabeçalho nulo não gera cabeçalho.
 */
@RegisterRestClient(configKey = "citizen")
@Path("/api/v1/cidadaos")
public interface CitizenClient {

    @POST
    @Path("/consulta")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    JsonNode consultar(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                       @HeaderParam("traceparent") String traceparent,
                       ConsultaRequest corpo);
}
