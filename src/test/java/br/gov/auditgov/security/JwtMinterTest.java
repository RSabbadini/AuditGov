package br.gov.auditgov.security;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confere o emissor de tokens DE TESTE por um caminho independente: a assinatura é verificada
 * aqui com a chave pública que a aplicação usa (src/main/resources/publicKey.pem), pela API de
 * assinaturas do Java, sem passar pelo Quarkus. Sem Quarkus, roda em milissegundos.
 *
 * Isto não substitui AutenticacaoTest, que prova que a APLICAÇÃO recusa tokens ruins.
 */
class JwtMinterTest {

    private static PublicKey chavePublicaDaAplicacao() throws Exception {
        try (InputStream in = JwtMinterTest.class.getResourceAsStream("/publicKey.pem")) {
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("-----[A-Z ]+-----", "")
                    .replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        }
    }

    private static boolean assinaturaConfere(String token, PublicKey chave) throws Exception {
        String[] partes = token.split("\\.", -1);
        Signature assinatura = Signature.getInstance("SHA256withRSA");
        assinatura.initVerify(chave);
        assinatura.update((partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII));
        return assinatura.verify(Base64.getUrlDecoder().decode(partes[2]));
    }

    private static String decodificar(String parte) {
        return new String(Base64.getUrlDecoder().decode(parte), StandardCharsets.UTF_8);
    }

    @Test
    void deveEmitirTokenComTresPartesAssinadoPelaChaveQueAAplicacaoConhece() throws Exception {
        String token = JwtMinter.novo().operador("fiscal_maria").emitir();

        String[] partes = token.split("\\.", -1);
        assertEquals(3, partes.length);
        assertEquals("{\"alg\":\"RS256\",\"typ\":\"JWT\"}", decodificar(partes[0]));
        assertTrue(assinaturaConfere(token, chavePublicaDaAplicacao()),
                "a assinatura deve conferir com a chave pública configurada na aplicação");
    }

    @Test
    void deveColocarOperadorEmUpnESubEOPapelEmGroups() {
        String token = JwtMinter.novo().operador("fiscal_maria").grupos("operador", "auditor").emitir();

        String corpo = decodificar(token.split("\\.", -1)[1]);
        assertTrue(corpo.contains("\"iss\":\"" + JwtMinter.EMISSOR_DEV + "\""), corpo);
        assertTrue(corpo.contains("\"sub\":\"fiscal_maria\""), corpo);
        assertTrue(corpo.contains("\"upn\":\"fiscal_maria\""), corpo);
        assertTrue(corpo.contains("\"groups\":[\"operador\",\"auditor\"]"), corpo);
        assertTrue(corpo.contains("\"jti\":\""), corpo);
    }

    @Test
    void somenteSubNaoDeveTerUpn() {
        String corpo = decodificar(JwtMinter.novo().somenteSub("so_sub").emitir().split("\\.", -1)[1]);

        assertTrue(corpo.contains("\"sub\":\"so_sub\""), corpo);
        assertFalse(corpo.contains("\"upn\""), corpo);
    }

    @Test
    void deveEscaparAspasBarrasEControlesNoJson() {
        String corpo = decodificar(JwtMinter.novo().operador("a\"b\\c\nd").emitir().split("\\.", -1)[1]);

        assertTrue(corpo.contains("\"upn\":\"a\\\"b\\\\c\\nd\""), corpo);
    }

    @Test
    void deveEmitirTokenExpiradoQuandoAValidadeENegativa() {
        long agora = Instant.now().getEpochSecond();
        String corpo = decodificar(JwtMinter.novo().operador("x").expiraEm(Duration.ofHours(-1)).emitir().split("\\.", -1)[1]);

        long exp = Long.parseLong(corpo.replaceAll(".*\"exp\":(\\d+).*", "$1"));
        assertTrue(exp < agora, "exp=" + exp + " deveria estar no passado (agora=" + agora + ")");
    }

    @Test
    void tokenAssinadoPorOutraChaveNaoConfereComAChaveDaAplicacao() throws Exception {
        KeyPair atacante = JwtMinter.gerarParDeChaves();
        String token = JwtMinter.novo().operador("x").assinarCom(atacante.getPrivate()).emitir();

        assertFalse(assinaturaConfere(token, chavePublicaDaAplicacao()));
        assertTrue(assinaturaConfere(token, atacante.getPublic()), "mas é um JWT bem formado");
    }

    @Test
    void tokenSemAssinaturaTemAlgNoneETerceiraParteVazia() {
        String token = JwtMinter.novo().operador("x").semAssinatura().emitir();

        String[] partes = token.split("\\.", -1);
        assertEquals(3, partes.length);
        assertEquals("{\"alg\":\"none\",\"typ\":\"JWT\"}", decodificar(partes[0]));
        assertEquals("", partes[2]);
    }
}
