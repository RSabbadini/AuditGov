package br.gov.auditgov.service;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.util.AuditChain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;

/**
 * Percorre {@code audit_log} em ordem de {@code seq} e confere a cadeia de hashes
 * ({@link AuditChain.Verifier}).
 *
 * A leitura é paginada por chave ({@code seq > último}), 1000 linhas por vez, e a memória
 * da sessão é esvaziada a cada página: o custo de memória não cresce com o tamanho da
 * tabela, só o tempo (um HMAC por registro). Gravações que chegam durante a verificação
 * não atrapalham: como cada gravação só solta o bloqueio depois de confirmada, os registros
 * ficam visíveis na ordem de {@code seq}, sem buracos transitórios.
 *
 * A verificação precisa da chave HMAC, que só a aplicação (ou quem tem a variável
 * AUDITGOV_HMAC_KEY) possui. Ela responde "a cadeia está íntegra?", não "quem alterou".
 */
@ApplicationScoped
public class ChainVerifierService {

    static final int PAGE_SIZE = 1_000;

    @Inject
    AuditCrypto crypto;

    @Inject
    EntityManager entityManager;

    /**
     * @param anchorSeq  posição de um registro cujo hash foi guardado fora do banco, ou nulo
     * @param anchorHash o hash guardado para essa posição, ou nulo (os dois juntos ou nenhum)
     */
    @Transactional
    public AuditChain.Result verify(Long anchorSeq, String anchorHash) {
        AuditChain.Verifier verifier = crypto.newChainVerifier(anchorSeq, anchorHash);
        long cursor = 0;
        while (!verifier.failed()) {
            List<AuditLog> page = AuditLog.find("from AuditLog where seq > ?1 order by seq", cursor)
                    .page(0, PAGE_SIZE)
                    .list();
            if (page.isEmpty()) {
                break;
            }
            for (AuditLog row : page) {
                if (!verifier.accept(row.toChainRow())) {
                    break;
                }
            }
            cursor = page.get(page.size() - 1).seq;
            entityManager.clear(); // as entidades são imutáveis; não há o que descartar com perda
        }
        return verifier.finish();
    }
}
