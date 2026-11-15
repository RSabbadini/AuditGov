package br.gov.auditgov.facade;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Formato do traceparent (W3C Trace Context, versão 00). Lógica pura, sem Quarkus. */
class TraceparentHeaderTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    void deveFormatarOExemploDaEspecificacaoW3C() {
        // Valores do exemplo da especificação W3C Trace Context.
        assertEquals("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                TraceparentHeader.formatar(TRACE, SPAN, true));
    }

    @Test
    void deveMarcarComoNaoAmostradoQuandoOSpanNaoEAmostrado() {
        assertEquals("00-" + TRACE + "-" + SPAN + "-00", TraceparentHeader.formatar(TRACE, SPAN, false));
    }

    @Test
    void deveRecusarTraceIdComTamanhoOuCaracteresInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> TraceparentHeader.formatar(null, SPAN, true));
        assertThrows(IllegalArgumentException.class, () -> TraceparentHeader.formatar("abc", SPAN, true));
        assertThrows(IllegalArgumentException.class,
                () -> TraceparentHeader.formatar(TRACE.toUpperCase(), SPAN, true));
        assertThrows(IllegalArgumentException.class,
                () -> TraceparentHeader.formatar("g" + TRACE.substring(1), SPAN, true));
        assertThrows(IllegalArgumentException.class, () -> TraceparentHeader.formatar(TRACE + "0", SPAN, true));
    }

    @Test
    void deveRecusarSpanIdComTamanhoOuCaracteresInvalidos() {
        assertThrows(IllegalArgumentException.class, () -> TraceparentHeader.formatar(TRACE, null, true));
        assertThrows(IllegalArgumentException.class, () -> TraceparentHeader.formatar(TRACE, "abc", true));
        assertThrows(IllegalArgumentException.class,
                () -> TraceparentHeader.formatar(TRACE, SPAN.toUpperCase(), true));
        assertThrows(IllegalArgumentException.class, () -> TraceparentHeader.formatar(TRACE, SPAN + "0", true));
    }

    @Test
    void deveRecusarIdsSoDeZeros() {
        // Inválidos pela especificação W3C.
        assertThrows(IllegalArgumentException.class,
                () -> TraceparentHeader.formatar("0".repeat(32), SPAN, true));
        assertThrows(IllegalArgumentException.class,
                () -> TraceparentHeader.formatar(TRACE, "0".repeat(16), true));
    }

    @Test
    void oNomeDoCabecalhoEOdaEspecificacao() {
        assertEquals("traceparent", TraceparentHeader.NOME);
    }
}
