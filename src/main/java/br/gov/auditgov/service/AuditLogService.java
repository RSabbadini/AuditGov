package br.gov.auditgov.service;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.util.AuditChain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Fronteira transacional da trilha de auditoria.
 *
 * Usa REQUIRES_NEW deliberadamente: a evidência precisa ser gravada ainda que
 * a transação de negócio do endpoint venha a sofrer rollback. Uma consulta a
 * dado pessoal que falhou na metade continua sendo um acesso ocorrido, e o
 * registro dele não pode desaparecer junto com o rollback da aplicação.
 *
 * <h2>Encadeamento e concorrência</h2>
 * Cada registro aponta para o hash do anterior ({@link AuditChain}). Para que a cadeia
 * seja uma lista, e não uma árvore, duas gravações simultâneas não podem ler a mesma
 * ponta. A gravação então:
 * <ol>
 *   <li>toma o bloqueio consultivo {@code pg_advisory_xact_lock(AuditChain.LOCK_KEY)},
 *       que vale até o fim da transação (confirmação ou desfazimento) e vale para
 *       TODAS as instâncias da aplicação ligadas ao mesmo banco;</li>
 *   <li>lê a ponta da cadeia (maior {@code seq}). No isolamento READ COMMITTED, padrão do
 *       PostgreSQL, essa leitura enxerga a confirmação da gravação anterior, porque o
 *       bloqueio só é liberado depois dela;</li>
 *   <li>atribui {@code seq = ponta + 1}, {@code prev_hash = hash da ponta} e calcula o
 *       {@code record_hash};</li>
 *   <li>insere a linha. A transação confirma ao sair do método e libera o bloqueio.</li>
 * </ol>
 * O custo é real: a parte "bloqueio até a confirmação" de todas as gravações forma uma fila
 * única, o que limita a vazão de eventos auditados (veja bench/README.md). A restrição de
 * unicidade de {@code seq} é a rede de segurança: se algo gravar sem tomar o bloqueio, o
 * segundo registro na mesma posição é recusado pelo banco em vez de bifurcar a cadeia.
 *
 * Se esta gravação falhar (por exemplo, PostgreSQL indisponível), o método lança
 * exceção e o AuditInterceptor decide o que fazer conforme auditgov.failure-mode.
 */
@ApplicationScoped
public class AuditLogService implements AuditEvidenceStore {

    private static final Logger LOG = Logger.getLogger(AuditLogService.class);

    /**
     * {@code pg_advisory_xact_lock} devolve {@code void}, que o Hibernate não sabe ler como
     * resultado de uma consulta nativa; o envoltório {@code count(*)} devolve um número comum.
     * O PostgreSQL não elimina a chamada: função volátil na lista de saída da subconsulta é
     * sempre avaliada.
     */
    private static final String LOCK_SQL = "select count(*) from (select pg_advisory_xact_lock(?1)) t";

    @Inject
    AuditCrypto crypto;

    @Inject
    EntityManager entityManager;

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void register(AuditLog auditLog) {
        if (auditLog.id == null) {
            // Fora do bloqueio: o que não depende da ponta da cadeia não deve alongar a fila.
            auditLog.id = UUID.randomUUID();
        }

        entityManager.createNativeQuery(LOCK_SQL)
                .setParameter(1, AuditChain.LOCK_KEY)
                .getSingleResult();

        AuditLog head = AuditLog.find("from AuditLog order by seq desc").firstResult();
        auditLog.seq = (head == null) ? 1L : head.seq + 1L;
        auditLog.prevHash = (head == null) ? AuditChain.GENESIS_HASH : head.recordHash;
        auditLog.recordHash = crypto.chainHash(auditLog.toChainRow());

        // Grava já (e não só na confirmação): um erro de INSERT aparece aqui, com a causa.
        auditLog.persistAndFlush();
        LOG.debugf("Evidência de auditoria gravada: %s", auditLog);
    }
}
