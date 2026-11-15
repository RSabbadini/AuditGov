package br.gov.auditgov.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Representação de saída de um benefício (dados fictícios).
 *
 * Existe para demonstrar um segundo endpoint auditado com a mesma anotação.
 */
public record BenefitDTO(
        @Schema(description = "Nome do programa (fictício).", example = "Programa Exemplo A")
        String programa,

        @Schema(description = "Situação do benefício.", example = "ATIVO")
        String situacao,

        @Schema(description = "Valor mensal em reais (fictício).", example = "600.00")
        BigDecimal valorMensal,

        @Schema(description = "Competência no formato yyyy-MM.", example = "2026-10")
        String competencia
) {
}
