package br.gov.auditgov.resource;

import br.gov.auditgov.annotation.Auditable;
import br.gov.auditgov.dto.CitizenDTO;
import br.gov.auditgov.dto.CpfQuery;
import br.gov.auditgov.util.Cpfs;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.Map;

/**
 * API GovTech simulada de consulta de dados cadastrais de cidadãos.
 *
 * Nenhuma linha deste recurso trata de auditoria: a trilha é transversal e é
 * acionada pelo AuditInterceptor por meio de @Auditable. É esse desacoplamento
 * que permite estender a auditoria a novos endpoints com uma única anotação
 * (veja também BenefitResource).
 *
 * O CPF é recebido no CORPO da requisição (POST), e não na URL, para que não
 * apareça no caminho gravado pelo OpenTelemetry nem em logs de servidores e proxies.
 *
 * O acesso exige um token JWT válido ({@code @Authenticated}): sem token, ou com token de
 * assinatura inválida, emissor errado ou expirado, a resposta é 401 e o método nem é
 * chamado, de modo que a tentativa não gera evidência de ACESSO A DADO (ela aparece só no
 * span HTTP do rastro). O operador gravado na trilha é o principal do token validado.
 *
 * As anotações MicroProfile OpenAPI (@Tag, @Operation, @APIResponses) documentam o
 * contrato para a extensão quarkus-smallrye-openapi, que o publica em /q/openapi
 * e /q/swagger-ui (base do Anexo A).
 */
@Path("/api/v1/cidadaos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Cidadãos", description = "Consulta de dados cadastrais. Todo acesso é auditado de " +
        "forma transversal pelo AuditGov (anotação @Auditable), sem código de auditoria " +
        "explícito neste recurso.")
public class CitizenResource {

    /** Base fictícia: substitua por um repositório real na integração. */
    private static final Map<String, CitizenDTO> BASE_MOCK = Map.of(
            "12345678900", new CitizenDTO(
                    "12345678900",
                    "Joana da Silva Pereira",
                    LocalDate.of(1988, 4, 12),
                    "Rua das Acácias, 120 - Centro - Campinas/SP - 13010-000"),
            "98765432100", new CitizenDTO(
                    "98765432100",
                    "Carlos Eduardo Ramos",
                    LocalDate.of(1975, 11, 30),
                    "Av. Paulista, 1578 - Bela Vista - São Paulo/SP - 01310-200")
    );

    /**
     * Consulta cadastral de um cidadão pelo CPF informado no corpo.
     *
     * @Blocking: o interceptador executa JDBC (PostgreSQL), que é bloqueante.
     * Com RESTEasy Reactive, métodos de retorno síncrono já vão para a worker
     * thread por padrão, mas a anotação explicita a intenção e protege contra
     * refatorações futuras para tipos reativos.
     *
     * @Transactional: abre a transação de negócio do endpoint. A evidência de
     * auditoria roda em REQUIRES_NEW, de modo que é confirmada mesmo que esta
     * transação sofra rollback.
     */
    @POST
    @Path("/consulta")
    @Blocking
    @Transactional
    @Auditable(action = "READ_CITIZEN_DATA")
    @Operation(
            summary = "Consulta cadastral de cidadão por CPF",
            description = "Retorna os dados cadastrais fictícios de um cidadão. A chamada é " +
                    "interceptada pelo AuditGov, que grava uma evidência no PostgreSQL (tabela " +
                    "somente-anexo, protegida por gatilho) e um span OpenTelemetry (Jaeger) com o " +
                    "mesmo trace_id. A evidência guarda o operador autenticado (principal do token " +
                    "JWT validado), o modelo de rota, uma referência HMAC do titular, um HMAC do " +
                    "conteúdo entregue e o encadeamento de hashes com o registro anterior; o CPF " +
                    "não é gravado em claro. Apoia o registro das " +
                    "operações de tratamento previsto no Art. 37 da LGPD, mas não garante " +
                    "conformidade por si só."
    )
    @RequestBody(required = true, description = "CPF a consultar.")
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Cidadão localizado.",
                    content = @Content(schema = @Schema(implementation = CitizenDTO.class))
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "CPF ausente ou fora do formato (11 dígitos). A tentativa é " +
                            "auditada (action = READ_CITIZEN_DATA_FAILED)."
            ),
            @APIResponse(
                    responseCode = "404",
                    description = "CPF não localizado na base cadastral. A tentativa também é " +
                            "auditada (action = READ_CITIZEN_DATA_FAILED): o esforço de acesso, " +
                            "mesmo sem sucesso, é fato relevante para a trilha."
            ),
            @APIResponse(
                    responseCode = "401",
                    description = "Token ausente, com assinatura inválida, de outro emissor ou expirado. " +
                            "A requisição não chega ao método e não gera evidência de acesso a dado."
            ),
            @APIResponse(
                    responseCode = "503",
                    description = "Só no modo estrito (auditgov.failure-mode=strict): a evidência " +
                            "não pôde ser gravada e, por isso, o dado não é entregue."
            )
    })
    public Response consultar(CpfQuery consulta) {
        String cpf = Cpfs.normalize(consulta == null ? null : consulta.cpf());
        if (cpf == null) {
            // Sem eco do valor recebido: a mensagem não repete o que o cliente enviou.
            throw new WebApplicationException("CPF ausente ou fora do formato (11 dígitos).",
                    Response.Status.BAD_REQUEST);
        }

        CitizenDTO cidadao = BASE_MOCK.get(cpf);
        if (cidadao == null) {
            // 404 sem eco do CPF consultado: evita vazar o identificador pesquisado
            // em logs de erro e em respostas de borda.
            throw new WebApplicationException("Cidadão não localizado na base cadastral.",
                    Response.Status.NOT_FOUND);
        }

        return Response.ok(cidadao).build();
    }
}
