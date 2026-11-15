package br.gov.auditgov.resource;

import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

/**
 * Declara o esquema de segurança do contrato OpenAPI (componente "bearerAuth"): token JWT
 * no cabeçalho {@code Authorization: Bearer ...}. A extensão quarkus-smallrye-openapi lê a
 * anotação e a publica em /q/openapi; os recursos a referenciam com
 * {@code @SecurityRequirement(name = "bearerAuth")}.
 *
 * Classe sem comportamento: existe só para carregar a anotação.
 */
@SecurityScheme(
        securitySchemeName = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Token JWT assinado (RS256) emitido pelo provedor de identidade. O operador "
                + "registrado na trilha é o principal do token (claim upn, preferred_username ou sub); "
                + "o papel vem da claim groups.")
final class OpenApiSecurity {

    private OpenApiSecurity() {
        // Só carrega a anotação.
    }
}
