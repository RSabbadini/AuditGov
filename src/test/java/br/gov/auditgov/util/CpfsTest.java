package br.gov.auditgov.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Testes de lógica pura (sem Quarkus) da normalização do CPF. */
class CpfsTest {

    @Test
    void deveAceitarCpfComOuSemPontuacao() {
        assertEquals("12345678900", Cpfs.normalize("12345678900"));
        assertEquals("12345678900", Cpfs.normalize("123.456.789-00"));
    }

    @Test
    void deveRejeitarFormatoInvalido() {
        assertNull(Cpfs.normalize(null));
        assertNull(Cpfs.normalize(""));
        assertNull(Cpfs.normalize("abc"));
        assertNull(Cpfs.normalize("123"));
        assertNull(Cpfs.normalize("123456789012"));
    }

    @Test
    void deveExtrairApenasDigitos() {
        assertEquals("123", Cpfs.digits("a1b2c3"));
        assertEquals("", Cpfs.digits(null));
    }
}
