package br.gov.auditgov.dto;

import br.gov.auditgov.annotation.AuditSubjectProvider;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Corpo das consultas por CPF.
 *
 * O CPF viaja no CORPO (POST) e não na URL. Assim ele não aparece no caminho da
 * requisição, que é gravado pela instrumentação HTTP do OpenTelemetry nos spans e
 * costuma aparecer em logs de servidores e proxies.
 *
 * Implementa {@link AuditSubjectProvider} para que o AuditInterceptor saiba qual
 * campo identifica o titular, sem que o recurso REST precise tratar auditoria.
 */
public record CpfQuery(
        @Schema(description = "CPF do titular, 11 dígitos (pontuação é ignorada).", example = "12345678900")
        String cpf
) implements AuditSubjectProvider {

    @Override
    public String auditSubjectId() {
        return cpf;
    }
}
