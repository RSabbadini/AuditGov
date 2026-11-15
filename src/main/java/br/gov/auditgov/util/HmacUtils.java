package br.gov.auditgov.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * HMAC-SHA-256 (RFC 2104, vetores de teste na RFC 4231).
 *
 * Por que HMAC e não um SHA-256 simples: um digest sem chave pode ser recalculado
 * por qualquer pessoa que conheça ou adivinhe o conteúdo. Com um CPF (10^11
 * combinações) ou com um cadastro conhecido, isso é um ataque de dicionário
 * barato. No HMAC o resultado só pode ser recalculado por quem tem a chave, que
 * deve ficar fora do banco de dados (variável de ambiente, cofre de segredos).
 *
 * O HMAC não torna o registro "anônimo" no sentido da LGPD: continua sendo um
 * dado pseudonimizado (Art. 13, §4º), pois quem tem a chave consegue ligar o
 * registro ao titular.
 */
public final class HmacUtils {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of(); // minúsculas por padrão

    private HmacUtils() {
        // Classe utilitária.
    }

    /** Retorna o HMAC-SHA-256 (32 bytes) de {@code data} com a chave {@code key}. */
    public static byte[] hmacSha256(byte[] key, byte[] data) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("A chave HMAC não pode ser vazia");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data == null ? new byte[0] : data);
        } catch (GeneralSecurityException e) {
            // HmacSHA256 é obrigatório em qualquer JVM compatível; falha aqui é irrecuperável.
            throw new IllegalStateException("Algoritmo " + ALGORITHM + " indisponível na JVM", e);
        }
    }

    /** Retorna o HMAC-SHA-256 em hexadecimal minúsculo (64 caracteres). */
    public static String hmacSha256Hex(byte[] key, byte[] data) {
        return HEX.formatHex(hmacSha256(key, data));
    }
}
