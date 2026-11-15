package br.gov.auditgov.resource;

import br.gov.auditgov.dto.ChainVerificationDTO;
import br.gov.auditgov.service.ChainVerifierService;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.regex.Pattern;

/**
 * Verificação da cadeia de hashes da trilha.
 *
 * Restrito ao papel {@code auditor} (claim {@code groups} do token). Este recurso NÃO é
 * {@code @Auditable}: não devolve dado pessoal, só contagens, posições e hashes.
 */
@Path("/api/v1/auditoria")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("auditor")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Auditoria", description = "Conferência da integridade da trilha de auditoria. "
        + "Exige o papel auditor.")
public class AuditChainResource {

    private static final Pattern HASH_HEX = Pattern.compile("[0-9a-f]{64}");

    @Inject
    ChainVerifierService verifier;

    @GET
    @Path("/cadeia/verificacao")
    @Blocking
    @Operation(
            summary = "Verifica a cadeia de hashes de audit_log",
            description = "Percorre todos os registros em ordem de seq e confere a sequência, o "
                    + "encadeamento (prev_hash) e o HMAC de cada registro. Aponta o primeiro registro "
                    + "que falha. Sem âncora, não detecta a remoção dos últimos registros nem a "
                    + "reescrita da cadeia por quem tem a chave HMAC: para isso, informe o par "
                    + "(ancoraSeq, ancoraHash) guardado fora do banco em uma verificação anterior."
    )
    @APIResponses({
            @APIResponse(
                    responseCode = "200",
                    description = "A verificação foi executada. O campo 'valida' diz se a cadeia confere.",
                    content = @Content(schema = @Schema(implementation = ChainVerificationDTO.class))
            ),
            @APIResponse(responseCode = "400",
                    description = "Âncora incompleta (informe ancoraSeq e ancoraHash juntos) ou fora do formato."),
            @APIResponse(responseCode = "401", description = "Token ausente, inválido ou expirado."),
            @APIResponse(responseCode = "403", description = "Token válido, mas sem o papel auditor.")
    })
    public ChainVerificationDTO verificar(
            @Parameter(description = "Posição (seq) do registro ancorado fora do banco.")
            @QueryParam("ancoraSeq") Long ancoraSeq,
            @Parameter(description = "record_hash (64 hexadecimais minúsculos) ancorado para essa posição.")
            @QueryParam("ancoraHash") String ancoraHash) {

        boolean temSeq = ancoraSeq != null;
        boolean temHash = ancoraHash != null && !ancoraHash.isBlank();
        if (temSeq != temHash) {
            throw new WebApplicationException("Informe ancoraSeq e ancoraHash juntos, ou nenhum dos dois.",
                    Response.Status.BAD_REQUEST);
        }
        if (temSeq && (ancoraSeq < 1 || !HASH_HEX.matcher(ancoraHash).matches())) {
            throw new WebApplicationException(
                    "Âncora fora do formato: seq >= 1 e hash com 64 hexadecimais minúsculos.",
                    Response.Status.BAD_REQUEST);
        }
        return ChainVerificationDTO.from(verifier.verify(temSeq ? ancoraSeq : null, temSeq ? ancoraHash : null));
    }
}
