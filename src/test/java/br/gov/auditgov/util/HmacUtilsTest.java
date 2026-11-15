package br.gov.auditgov.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sem @QuarkusTest de propósito: HmacUtils não depende de CDI, então o teste roda
 * em milissegundos. Os dois primeiros casos são vetores oficiais da RFC 4231.
 */
class HmacUtilsTest {

    @Test
    void deveReproduzirVetorDeTesteUmDaRfc4231() {
        byte[] chave = new byte[20];
        Arrays.fill(chave, (byte) 0x0b);

        String mac = HmacUtils.hmacSha256Hex(chave, "Hi There".getBytes(StandardCharsets.US_ASCII));

        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", mac);
    }

    @Test
    void deveReproduzirVetorDeTesteDoisDaRfc4231() {
        String mac = HmacUtils.hmacSha256Hex(
                "Jefe".getBytes(StandardCharsets.US_ASCII),
                "what do ya want for nothing?".getBytes(StandardCharsets.US_ASCII));

        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac);
    }

    @Test
    void deveGerarResultadosDiferentesParaChavesDiferentes() {
        byte[] dado = "12345678900".getBytes(StandardCharsets.UTF_8);

        String macA = HmacUtils.hmacSha256Hex("chave-A-0123456789".getBytes(StandardCharsets.UTF_8), dado);
        String macB = HmacUtils.hmacSha256Hex("chave-B-0123456789".getBytes(StandardCharsets.UTF_8), dado);

        assertEquals(64, macA.length());
        assertNotEquals(macA, macB, "sem a chave não é possível recalcular o valor");
    }

    @Test
    void deveRecusarChaveVazia() {
        byte[] dado = "x".getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> HmacUtils.hmacSha256Hex(new byte[0], dado));
        assertThrows(IllegalArgumentException.class, () -> HmacUtils.hmacSha256Hex(null, dado));
    }
}
