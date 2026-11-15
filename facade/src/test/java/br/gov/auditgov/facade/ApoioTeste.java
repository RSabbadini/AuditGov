package br.gov.auditgov.facade;

import java.util.UUID;

/** Apoio comum aos testes da fachada. */
final class ApoioTeste {

    static final String ROTA = "/api/v1/portal/consulta";
    static final String CPF_EXISTENTE = "12345678900";

    private ApoioTeste() {
        // Classe utilitária.
    }

    /** Trace ID W3C: 32 caracteres hexadecimais minúsculos. */
    static String traceIdAleatorio() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Span ID W3C: 16 caracteres hexadecimais minúsculos. */
    static String spanIdAleatorio() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Corpo JSON {"cpf": "..."}. */
    static String corpoCpf(String cpf) {
        return "{\"cpf\":\"" + cpf + "\"}";
    }
}
