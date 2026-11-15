package br.gov.auditgov.service;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Avisa quando a aplicação sobe em modo de produção confiando no emissor de LABORATÓRIO do JWT.
 *
 * O par privado da chave de laboratório ({@code publicKey.pem}) está no repositório
 * ({@code src/test/resources/jwt/}): quem o tiver emite tokens aceitos, com qualquer operador e
 * qualquer papel. Em dev e test isso é o desejado. Em produção ({@link LaunchMode#NORMAL}) a chave
 * e o emissor vêm só de {@code AUDITGOV_JWT_PUBLIC_KEY_LOCATION} e {@code AUDITGOV_JWT_ISSUER},
 * sem valor padrão (perfil {@code %prod} do application.properties): sem elas o Quarkus nem sobe.
 * Se alguém apontar de propósito para a chave de laboratório (como no bench/), a aplicação sobe
 * e este aviso fica no log.
 */
@Startup
@ApplicationScoped
public class JwtConfigGuard {

    static final String LAB_KEY = "publicKey.pem";
    static final String LAB_ISSUER = "https://auditgov.local/dev-issuer";

    private static final Logger LOG = Logger.getLogger(JwtConfigGuard.class);

    @ConfigProperty(name = "mp.jwt.verify.publickey.location")
    String location;

    @ConfigProperty(name = "mp.jwt.verify.issuer")
    String issuer;

    @PostConstruct
    void init() {
        if (LaunchMode.current() == LaunchMode.NORMAL && usaEmissorDeLaboratorio(location, issuer)) {
            LOG.warn("Modo de produção usando o emissor de LABORATÓRIO do JWT (o par privado está no "
                    + "repositório). Aceitável só em laboratório e medição; fora disso, aponte "
                    + "AUDITGOV_JWT_PUBLIC_KEY_LOCATION e AUDITGOV_JWT_ISSUER para o IdP real.");
        }
    }

    static boolean usaEmissorDeLaboratorio(String location, String issuer) {
        String l = location == null ? "" : location.trim();
        boolean chaveLab = l.equals(LAB_KEY) || l.equals("classpath:" + LAB_KEY) || l.equals("/" + LAB_KEY);
        return chaveLab || LAB_ISSUER.equals(issuer == null ? null : issuer.trim());
    }
}
