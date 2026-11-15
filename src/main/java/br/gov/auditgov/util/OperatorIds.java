package br.gov.auditgov.util;

/**
 * Normalização do identificador do operador (nome do principal do token JWT validado).
 *
 * O valor vem de uma claim assinada, mas o emissor do token decide o texto. Antes de
 * gravar, ele é limpo para que (1) caracteres de controle não permitam injeção de linhas
 * falsas em logs e não quebrem o formato canônico da cadeia de hashes, e (2) um valor
 * maior que a coluna não faça a gravação falhar e a evidência se perder. A limpeza não
 * autentica nada: quem autentica é a validação da assinatura do token.
 */
public final class OperatorIds {

    public static final String ANONYMOUS_OPERATOR = "ANONYMOUS_OPERATOR";

    /** Tamanho da coluna operator_id em audit_log. */
    public static final int MAX_LENGTH = 120;

    private OperatorIds() {
        // Classe utilitária.
    }

    /**
     * Remove caracteres de controle e espaços nas pontas e limita o tamanho.
     * Devolve {@link #ANONYMOUS_OPERATOR} se não sobrar nenhum texto.
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return ANONYMOUS_OPERATOR;
        }
        String cleaned = raw.replaceAll("\\p{Cc}", "").strip();
        if (cleaned.isEmpty()) {
            return ANONYMOUS_OPERATOR;
        }
        if (cleaned.length() <= MAX_LENGTH) {
            return cleaned;
        }
        int end = MAX_LENGTH;
        // Não corta um par substituto (caractere fora do plano básico) ao meio.
        if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
            end--;
        }
        return cleaned.substring(0, end).strip();
    }
}
