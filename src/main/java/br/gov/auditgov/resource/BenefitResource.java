package br.gov.auditgov.resource;

import br.gov.auditgov.annotation.Auditable;
import br.gov.auditgov.dto.BenefitDTO;
import br.gov.auditgov.dto.CpfQuery;
import br.gov.auditgov.util.Cpfs;
import io.quarkus.security.Authenticated;
import io.smallrye.common.annotation.Blocking;
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

import java.math.BigDecimal;
import java.util.Map;

/**
 * Segundo endpoint auditado (dados fictícios), para mostrar que estender a
 * auditoria a um novo recurso exige apenas @Auditable no método e que o objeto de
 * entrada declare o titular (CpfQuery implementa AuditSubjectProvider).
 */
@Path("/api/v1/beneficios")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Benefícios", description = "Consulta de benefício de um titular (dados fictícios). " +
        "Auditada pelo mesmo mecanismo transversal do recurso de cidadãos.")
public class BenefitResource {

    /** Base fictícia. */
    private static final Map<String, BenefitDTO> BASE_MOCK = Map.of(
            "12345678900", new BenefitDTO("Programa Exemplo A", "ATIVO", new BigDecimal("600.00"), "2026-10")
    );

    @POST
    @Path("/consulta")
    @Blocking
    @Auditable(action = "READ_BENEFIT_DATA")
    @Operation(
            summary = "Consulta de benefício por CPF",
            description = "Retorna o benefício fictício associado ao CPF informado no corpo. " +
                    "Auditada pelo AuditGov com a ação READ_BENEFIT_DATA."
    )
    @RequestBody(required = true, description = "CPF a consultar.")
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "Benefício localizado.",
                    content = @Content(schema = @Schema(implementation = BenefitDTO.class))
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "CPF ausente ou fora do formato (11 dígitos). A tentativa é auditada."
            ),
            @APIResponse(
                    responseCode = "404",
                    description = "Nenhum benefício para o CPF. A tentativa também é auditada."
            ),
            @APIResponse(
                    responseCode = "401",
                    description = "Token ausente, inválido ou expirado. Não gera evidência de acesso a dado."
            ),
            @APIResponse(
                    responseCode = "503",
                    description = "Só no modo estrito: a evidência não pôde ser gravada e o dado não é entregue."
            )
    })
    public Response consultar(CpfQuery consulta) {
        String cpf = Cpfs.normalize(consulta == null ? null : consulta.cpf());
        if (cpf == null) {
            throw new WebApplicationException("CPF ausente ou fora do formato (11 dígitos).",
                    Response.Status.BAD_REQUEST);
        }
        BenefitDTO beneficio = BASE_MOCK.get(cpf);
        if (beneficio == null) {
            throw new WebApplicationException("Benefício não localizado.", Response.Status.NOT_FOUND);
        }
        return Response.ok(beneficio).build();
    }
}
