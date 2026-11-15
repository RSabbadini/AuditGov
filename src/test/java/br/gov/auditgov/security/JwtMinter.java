package br.gov.auditgov.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emissor de tokens JWT SÓ PARA TESTES, usando apenas a biblioteca padrão do Java.
 *
 * Os tokens são assinados com RS256 pela chave privada de laboratório em
 * {@code src/test/resources/jwt/auditgov-dev-private.pem}, cuja chave pública é a que a
 * aplicação confia ({@code src/main/resources/publicKey.pem}). Esta classe NÃO faz parte da
 * aplicação: em produção quem emite tokens é o provedor de identidade, e a aplicação só os valida.
 *
 * O construtor permite fabricar também tokens defeituosos (expirado, de outro emissor, assinado
 * por outra chave, sem assinatura) para provar que a aplicação os recusa.
 */
public final class JwtMinter {

    /** Emissor que a aplicação aceita (mp.jwt.verify.issuer). */
    public static final String EMISSOR_DEV = "https://auditgov.local/dev-issuer";

    private static final String RECURSO_CHAVE_PRIVADA = "/jwt/auditgov-dev-private.pem";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    private JwtMinter() {
        // Use novo().
    }

    public static Builder novo() {
        return new Builder();
    }

    /** Par de chaves RSA 2048 novo, para simular um atacante que não tem a chave do emissor. */
    public static KeyPair gerarParDeChaves() {
        try {
            KeyPairGenerator gerador = KeyPairGenerator.getInstance("RSA");
            gerador.initialize(2048);
            return gerador.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Carrega a chave privada de laboratório (uma vez, na primeira necessidade). */
    private static final class ChaveDev {
        static final PrivateKey CHAVE = carregar();

        private static PrivateKey carregar() {
            try (InputStream in = JwtMinter.class.getResourceAsStream(RECURSO_CHAVE_PRIVADA)) {
                if (in == null) {
                    throw new IllegalStateException("Chave de teste ausente no classpath: " + RECURSO_CHAVE_PRIVADA);
                }
                String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replaceAll("-----[A-Z ]+-----", "")
                        .replaceAll("\\s", "");
                return KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("Não foi possível ler a chave privada de teste", e);
            }
        }
    }

    public static final class Builder {

        private String upn;
        private String sub;
        private String emissor = EMISSOR_DEV;
        private List<String> grupos = List.of("operador");
        private long emitidoEm = Instant.now().getEpochSecond();
        private long expiraEm = emitidoEm + 3600;
        private PrivateKey chave;
        private boolean semAssinatura;

        /** Define {@code upn} e {@code sub} com o mesmo nome (caso comum). */
        public Builder operador(String nome) {
            this.upn = nome;
            this.sub = nome;
            return this;
        }

        /** Só a claim {@code sub}, sem {@code upn}: o principal passa a ser o {@code sub}. */
        public Builder somenteSub(String nome) {
            this.upn = null;
            this.sub = nome;
            return this;
        }

        /** Papéis do token (claim {@code groups}). Padrão: "operador". */
        public Builder grupos(String... papeis) {
            this.grupos = Arrays.asList(papeis);
            return this;
        }

        public Builder emissor(String valor) {
            this.emissor = valor;
            return this;
        }

        /** Validade a partir de agora; uma duração negativa gera um token já expirado. */
        public Builder expiraEm(Duration validade) {
            this.expiraEm = Instant.now().getEpochSecond() + validade.getSeconds();
            return this;
        }

        /** Assina com outra chave privada (o servidor não a conhece e deve recusar). */
        public Builder assinarCom(PrivateKey outraChave) {
            this.chave = outraChave;
            return this;
        }

        /** Gera um token com {@code alg=none} e assinatura vazia. */
        public Builder semAssinatura() {
            this.semAssinatura = true;
            return this;
        }

        public String emitir() {
            Map<String, Object> cabecalho = new LinkedHashMap<>();
            cabecalho.put("alg", semAssinatura ? "none" : "RS256");
            cabecalho.put("typ", "JWT");

            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", emissor);
            if (sub != null) {
                claims.put("sub", sub);
            }
            if (upn != null) {
                claims.put("upn", upn);
            }
            claims.put("groups", grupos);
            claims.put("iat", emitidoEm);
            claims.put("exp", expiraEm);
            claims.put("jti", UUID.randomUUID().toString());

            String corpo = base64(json(cabecalho)) + "." + base64(json(claims));
            if (semAssinatura) {
                return corpo + ".";
            }
            return corpo + "." + BASE64_URL.encodeToString(assinar(corpo, chave != null ? chave : ChaveDev.CHAVE));
        }

        private static byte[] assinar(String conteudo, PrivateKey chave) {
            try {
                Signature assinatura = Signature.getInstance("SHA256withRSA"); // RS256
                assinatura.initSign(chave);
                assinatura.update(conteudo.getBytes(StandardCharsets.US_ASCII));
                return assinatura.sign();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON mínimo (strings, números e listas de strings bastam para as claims)
    // ------------------------------------------------------------------

    private static String base64(String json) {
        return BASE64_URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    static String json(Map<String, Object> campos) {
        StringBuilder out = new StringBuilder("{");
        boolean primeiro = true;
        for (Map.Entry<String, Object> campo : campos.entrySet()) {
            if (!primeiro) {
                out.append(',');
            }
            primeiro = false;
            out.append(texto(campo.getKey())).append(':').append(valor(campo.getValue()));
        }
        return out.append('}').toString();
    }

    private static String valor(Object valor) {
        if (valor instanceof Number) {
            return valor.toString();
        }
        if (valor instanceof List<?> lista) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < lista.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(texto(String.valueOf(lista.get(i))));
            }
            return out.append(']').toString();
        }
        return texto(String.valueOf(valor));
    }

    private static String texto(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
