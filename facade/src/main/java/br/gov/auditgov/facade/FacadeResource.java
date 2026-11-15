package br.gov.auditgov.facade;

import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Fachada mínima do AuditGov: recebe a consulta do "portal" e a repassa ao serviço de cidadãos.
 *
 * Existe para demonstrar a PROPAGAÇÃO do contexto de rastreamento entre dois serviços: a
 * requisição que chega aqui já abre um span; a chamada ao serviço de cidadãos leva o cabeçalho
 * W3C {@code traceparent}; o serviço de cidadãos continua o MESMO trace e grava em
 * {@code audit_log} o mesmo {@code trace_id} que o Jaeger mostra, com spans dos dois serviços.
 *
 * O que a fachada faz e NÃO faz:
 * <ul>
 *   <li>Repassa o {@code Authorization} recebido. Ela NÃO valida o token: quem o valida (e
 *       responde 401) é o serviço de cidadãos, que guarda o dado e grava a evidência.</li>
 *   <li>Repassa só o STATUS dos erros do serviço de cidadãos (401, 403, 404, 400, 503...),
 *       nunca o corpo. Falha de conexão vira 502.</li>
 *   <li>Não grava nem registra em log o CPF nem o dado devolvido. O CPF viaja no corpo.</li>
 *   <li>Não repassa o X-User-ID (a identidade vem só do token).</li>
 *   <li>Não cria auditoria própria: a evidência de acesso a dado é a do serviço de cidadãos.</li>
 * </ul>
 */
@Path("/api/v1/portal")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FacadeResource {

    private static final Logger LOG = Logger.getLogger(FacadeResource.class);

    @Inject
    @RestClient
    CitizenClient cidadaos;

    /**
     * Contingência: se a instrumentação automática do Quarkus não injetar o traceparent na
     * chamada ao serviço de cidadãos, ligue esta opção para a fachada montá-lo a partir do
     * span atual. Padrão: desligada.
     */
    @ConfigProperty(name = "facade.trace.manual-propagation", defaultValue = "false")
    boolean propagacaoManual;

    /**
     * @Blocking: o cliente REST usado aqui é síncrono; a chamada precisa rodar em worker thread.
     */
    @POST
    @Path("/consulta")
    @Blocking
    public Response consultar(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                              ConsultaRequest corpo) {
        if (corpo == null || corpo.cpf() == null || corpo.cpf().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        String traceparent = propagacaoManual ? TraceparentHeader.doSpanAtual() : null;
        try {
            JsonNode cidadao = cidadaos.consultar(authorization, traceparent, corpo);
            return Response.ok(cidadao).build();
        } catch (WebApplicationException e) {
            // O serviço de cidadãos respondeu com erro HTTP: repassa o status, sem o corpo.
            int status = e.getResponse().getStatus();
            LOG.debugf("Serviço de cidadãos respondeu HTTP %d; a fachada repassa só o status.", status);
            return Response.status(status).build();
        } catch (RuntimeException e) {
            // Sem resposta HTTP (conexão recusada, tempo esgotado...). Só o tipo da falha vai
            // para o log: nada do corpo da requisição.
            LOG.warnf("Serviço de cidadãos inacessível (%s).", e.getClass().getSimpleName());
            return Response.status(Response.Status.BAD_GATEWAY).build();
        }
    }
}
