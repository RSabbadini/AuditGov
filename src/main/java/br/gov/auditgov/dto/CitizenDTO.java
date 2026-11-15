package br.gov.auditgov.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Representação de saída de um cidadão (dados mockados).
 *
 * Record Java 21: imutável e serializado nativamente pelo Jackson no Quarkus.
 * O conteúdo deste objeto é dado pessoal na acepção do Art. 5º, I da LGPD.
 * Ele trafega até o operador, mas não é gravado na trilha de auditoria: a
 * trilha guarda apenas o HMAC-SHA-256 do conteúdo entregue.
 *
 * As anotações @Schema abaixo não são decorativas: é a partir delas que a
 * extensão quarkus-smallrye-openapi compõe o componente "CitizenDTO" do
 * contrato OpenAPI publicado em /q/openapi (Anexo A).
 */
public record CitizenDTO(
        @Schema(description = "CPF do cidadão, apenas dígitos, sem máscara.", example = "12345678900")
        String cpf,

        @Schema(description = "Nome completo do cidadão.", example = "Joana da Silva Pereira")
        String nome,

        @Schema(description = "Data de nascimento, formato ISO-8601 (yyyy-MM-dd).", example = "1988-04-12")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate dataNascimento,

        @Schema(description = "Endereço completo (logradouro, bairro, cidade/UF, CEP).",
                example = "Rua das Acácias, 120 - Centro - Campinas/SP - 13010-000")
        String endereco
) {
}
