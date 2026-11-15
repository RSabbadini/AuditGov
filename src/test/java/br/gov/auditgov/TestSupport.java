package br.gov.auditgov;

import br.gov.auditgov.security.JwtMinter;

import java.util.UUID;

/**
 * Apoio comum aos testes de integração.
 *
 * Como o gatilho append-only impede apagar linhas de audit_log, os testes NÃO
 * limpam a tabela. Cada teste se isola usando um operador (ou um trace id)
 * único, gerado aqui, e consulta apenas as linhas que ele mesmo criou.
 */
public final class TestSupport {

    public static final String CPF_EXISTENTE = "12345678900";
    public static final String CPF_INEXISTENTE = "00000000000";
    public static final String ROTA_CIDADAOS = "/api/v1/cidadaos/consulta";
    public static final String ROTA_BENEFICIOS = "/api/v1/beneficios/consulta";
    public static final String ROTA_VERIFICACAO_CADEIA = "/api/v1/auditoria/cadeia/verificacao";
    public static final String AUTORIZACAO = "Authorization";

    private TestSupport() {
        // Classe utilitária.
    }

    /**
     * Valor do cabeçalho Authorization para um token VÁLIDO do operador (papel "operador").
     * O operador gravado na trilha é o upn do token, então o nome único do teste continua
     * sendo o que isola as evidências de cada teste.
     */
    public static String bearer(String operador) {
        return "Bearer " + JwtMinter.novo().operador(operador).emitir();
    }

    /** Token válido com o papel "auditor" (acesso à verificação da cadeia). */
    public static String bearerAuditor(String operador) {
        return "Bearer " + JwtMinter.novo().operador(operador).grupos("auditor").emitir();
    }

    /** Operador único por teste, por exemplo {@code fiscal_3fa85f64}. */
    public static String operador(String prefixo) {
        return prefixo + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Trace ID W3C: 32 caracteres hexadecimais minúsculos. */
    public static String traceIdAleatorio() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Span ID W3C: 16 caracteres hexadecimais minúsculos. */
    public static String spanIdAleatorio() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** Corpo JSON {"cpf": "..."}. */
    public static String corpoCpf(String cpf) {
        return "{\"cpf\":\"" + cpf + "\"}";
    }
}
