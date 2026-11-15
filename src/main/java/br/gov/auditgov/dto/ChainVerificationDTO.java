package br.gov.auditgov.dto;

import br.gov.auditgov.util.AuditChain;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Resultado da verificação da cadeia de hashes da trilha.
 *
 * Não contém dado pessoal: só contagens, posições e hashes (HMAC).
 */
@Schema(name = "VerificacaoDaCadeia",
        description = "Resultado da conferência da cadeia de hashes de audit_log.")
public record ChainVerificationDTO(
        @Schema(description = "true se todos os registros conferem (sequência, encadeamento e hash).",
                example = "true")
        boolean valida,

        @Schema(description = "Quantos registros foram aprovados antes de terminar ou de falhar.", example = "128")
        long registrosVerificados,

        @Schema(description = "Posição (seq) do último registro aprovado; nulo se a tabela está vazia.",
                example = "128")
        Long ultimoSeq,

        @Schema(description = "record_hash do último registro aprovado. Guarde este par (seq, hash) FORA do "
                + "banco para detectar, depois, a remoção dos registros finais.")
        String ultimoHash,

        @Schema(description = "Posição do primeiro registro que falhou; nulo se a cadeia é válida.", example = "57")
        Long seqComFalha,

        @Schema(description = "Motivo da falha: SEQ_GAP, PREV_HASH_MISMATCH, RECORD_HASH_MISMATCH, "
                + "ANCHOR_MISMATCH ou TAIL_TRUNCATED; nulo se a cadeia é válida.",
                example = "RECORD_HASH_MISMATCH")
        String motivo,

        @Schema(description = "Explicação da falha; nulo se a cadeia é válida.")
        String detalhe
) {

    public static ChainVerificationDTO from(AuditChain.Result result) {
        return new ChainVerificationDTO(
                result.valid(),
                result.checked(),
                result.headSeq(),
                result.headHash(),
                result.failedSeq(),
                result.reason() == null ? null : result.reason().name(),
                result.detail());
    }
}
