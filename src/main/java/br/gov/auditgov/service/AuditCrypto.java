package br.gov.auditgov.service;

import br.gov.auditgov.util.AuditChain;
import br.gov.auditgov.util.Cpfs;
import br.gov.auditgov.util.HmacUtils;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;

/**
 * Operações criptográficas da trilha: pseudonimização do titular, impressão
 * digital do conteúdo entregue e encadeamento dos registros. Todas usam HMAC-SHA-256.
 *
 * A chave mestra vem da configuração {@code auditgov.hmac.key}, que deve ser
 * preenchida pela variável de ambiente {@code AUDITGOV_HMAC_KEY} (ou por um cofre
 * de segredos) e NUNCA ficar no banco de dados. Dela são derivadas três subchaves
 * independentes, uma para cada finalidade (separação de domínio), de modo que o
 * valor de um campo não serve para atacar o outro. A subchave da cadeia é o que
 * impede quem altera o banco de recalcular os hashes e esconder a alteração.
 *
 * Em modo de produção ({@link LaunchMode#NORMAL}) a aplicação se recusa a subir
 * com a chave de desenvolvimento.
 */
@Startup
@ApplicationScoped
public class AuditCrypto {

    static final String DEV_KEY = "auditgov-dev-key-nao-usar-em-producao";
    private static final int MIN_KEY_BYTES = 16;
    private static final Logger LOG = Logger.getLogger(AuditCrypto.class);

    @ConfigProperty(name = "auditgov.hmac.key")
    String masterKey;

    private byte[] subjectKey;
    private byte[] payloadKey;
    private byte[] chainKey;

    @PostConstruct
    void init() {
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException("Defina auditgov.hmac.key (variável AUDITGOV_HMAC_KEY)");
        }
        byte[] master = masterKey.getBytes(StandardCharsets.UTF_8);
        if (master.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "auditgov.hmac.key deve ter pelo menos " + MIN_KEY_BYTES + " bytes");
        }
        if (DEV_KEY.equals(masterKey)) {
            if (LaunchMode.current() == LaunchMode.NORMAL) {
                throw new IllegalStateException(
                        "A chave HMAC de desenvolvimento não pode ser usada em produção: "
                                + "defina AUDITGOV_HMAC_KEY");
            }
            LOG.warn("Usando a chave HMAC de desenvolvimento; defina AUDITGOV_HMAC_KEY fora do laboratório.");
        }
        subjectKey = HmacUtils.hmacSha256(master, "auditgov/subject-ref/v1".getBytes(StandardCharsets.UTF_8));
        payloadKey = HmacUtils.hmacSha256(master, "auditgov/payload-hash/v1".getBytes(StandardCharsets.UTF_8));
        chainKey = HmacUtils.hmacSha256(master, "auditgov/audit-chain/v1".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Referência pseudonimizada do titular: HMAC-SHA-256 dos dígitos do
     * identificador (64 caracteres hexadecimais), ou {@code null} se não houver
     * nenhum dígito. O mesmo CPF, com ou sem pontuação, gera a mesma referência.
     */
    public String subjectRef(String rawSubject) {
        String digits = Cpfs.digits(rawSubject);
        if (digits.isEmpty()) {
            return null;
        }
        return HmacUtils.hmacSha256Hex(subjectKey, digits.getBytes(StandardCharsets.UTF_8));
    }

    /** Impressão digital com chave do conteúdo entregue (64 caracteres hexadecimais). */
    public String payloadHash(byte[] payload) {
        return HmacUtils.hmacSha256Hex(payloadKey, payload == null ? new byte[0] : payload);
    }

    /**
     * {@code record_hash} do registro: HMAC-SHA-256 do formato canônico da cadeia
     * ({@link AuditChain}), 64 caracteres hexadecimais. A chave não sai desta classe.
     */
    public String chainHash(AuditChain.Row row) {
        return AuditChain.hash(chainKey, row);
    }

    /**
     * Verificador da cadeia com a subchave desta aplicação.
     *
     * @param anchorSeq  posição de um registro ancorado fora do banco, ou nulo
     * @param anchorHash hash ancorado para essa posição, ou nulo (os dois juntos ou nenhum)
     */
    public AuditChain.Verifier newChainVerifier(Long anchorSeq, String anchorHash) {
        return new AuditChain.Verifier(chainKey, anchorSeq, anchorHash);
    }
}
