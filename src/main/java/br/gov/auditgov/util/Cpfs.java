package br.gov.auditgov.util;

/**
 * Normalização de CPF informado pelo cliente.
 *
 * Valida apenas o formato (11 dígitos). Não valida os dígitos verificadores de
 * propósito: a base de exemplo usa CPFs fictícios que não passariam nessa
 * verificação.
 */
public final class Cpfs {

    private Cpfs() {
        // Classe utilitária.
    }

    /** Mantém só os dígitos. Devolve texto vazio se a entrada for nula. */
    public static String digits(String raw) {
        return raw == null ? "" : raw.replaceAll("\\D", "");
    }

    /** Devolve os 11 dígitos do CPF, ou {@code null} se o formato for inválido. */
    public static String normalize(String raw) {
        String digits = digits(raw);
        return digits.length() == 11 ? digits : null;
    }
}
