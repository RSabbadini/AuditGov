package br.gov.auditgov.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Testes de lógica pura (sem Quarkus) da limpeza do identificador do operador (principal do token). */
class OperatorIdsTest {

    @Test
    void deveUsarOperadorAnonimoParaValorNuloOuEmBranco() {
        assertEquals(OperatorIds.ANONYMOUS_OPERATOR, OperatorIds.sanitize(null));
        assertEquals(OperatorIds.ANONYMOUS_OPERATOR, OperatorIds.sanitize(""));
        assertEquals(OperatorIds.ANONYMOUS_OPERATOR, OperatorIds.sanitize("   "));
    }

    @Test
    void deveManterValorNormalRemovendoEspacosDasPontas() {
        assertEquals("fiscal_maria", OperatorIds.sanitize("  fiscal_maria "));
    }

    @Test
    void deveRemoverCaracteresDeControleQuePermitiriamInjecaoEmLog() {
        String limpo = OperatorIds.sanitize("maria\r\n2026-01-01 ERROR falso\t!");

        assertFalse(limpo.contains("\n"));
        assertFalse(limpo.contains("\r"));
        assertFalse(limpo.contains("\t"));
        assertEquals("maria2026-01-01 ERROR falso!", limpo);
    }

    @Test
    void deveLimitarAoTamanhoDaColuna() {
        String limpo = OperatorIds.sanitize("x".repeat(500));

        assertEquals(OperatorIds.MAX_LENGTH, limpo.length());
    }

    @Test
    void naoDeveCortarParSubstitutoAoMeio() {
        // 119 letras + um caractere fora do plano básico (2 unidades): o corte cairia no meio dele.
        String entrada = "a".repeat(119) + "😀" + "fim";

        String limpo = OperatorIds.sanitize(entrada);

        assertEquals(119, limpo.length());
        assertTrue(limpo.chars().allMatch(c -> c == 'a'));
    }
}
