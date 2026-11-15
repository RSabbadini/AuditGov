package br.gov.auditgov.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Encadeamento de hashes da trilha de auditoria (lógica pura, sem CDI nem banco).
 *
 * Cada registro guarda o hash do registro anterior ({@code prev_hash}) e o seu próprio hash
 * ({@code record_hash}), calculado sobre o conteúdo do registro e sobre o {@code prev_hash}.
 * Alterar, remover, inserir ou reordenar um registro rompe a cadeia a partir dele, e a
 * verificação ({@link Verifier}) aponta onde.
 *
 * <h2>O que isto prova e o que não prova</h2>
 * <ul>
 *   <li>É evidência de adulteração (<i>tamper-evidence</i>), não prevenção: quem altera o
 *       banco continua conseguindo alterar; o que muda é que a alteração fica detectável.</li>
 *   <li>O hash usa HMAC-SHA-256 com uma chave que NÃO está no banco. Sem a chave, quem altera
 *       uma linha não consegue recalcular os hashes seguintes e esconder a alteração. Com um
 *       SHA-256 simples, bastaria recalcular a cadeia inteira.</li>
 *   <li>Quem tem a chave E acesso de escrita ao banco consegue reescrever a cadeia. Para esse
 *       caso, e para a remoção dos últimos registros (que deixa uma cadeia íntegra, só mais
 *       curta), é preciso uma âncora externa: o par (seq, record_hash) da ponta da cadeia,
 *       guardado fora do banco. Veja {@link Verifier#Verifier(byte[], Long, String)}.</li>
 * </ul>
 *
 * <h2>Formato canônico (versão 1)</h2>
 * Texto UTF-8 com os campos abaixo separados por LF (sem LF final), nesta ordem:
 * <pre>
 * auditgov-chain-v1
 * seq                (decimal)
 * id                 (UUID em minúsculas, com hifens)
 * trace_id
 * span_id
 * operator_id
 * action
 * resource
 * subject_ref        (vazio se nulo)
 * payload_hash
 * event_timestamp    (microssegundos desde 1970-01-01T00:00:00Z, decimal)
 * prev_hash
 * </pre>
 * {@code record_hash = HMAC-SHA-256(chave_da_cadeia, canônico)} em hexadecimal minúsculo. Como
 * LF é o separador, nenhum campo pode conter LF ou CR; o operador já é limpo por
 * {@link OperatorIds#sanitize(String)}, e um campo com LF é recusado aqui em vez de ser gravado
 * de forma ambígua. O carimbo de tempo é gravado e hasheado com precisão de microssegundos,
 * a mesma da coluna {@code timestamptz(6)} do PostgreSQL.
 */
public final class AuditChain {

    /** Valor de {@code prev_hash} do primeiro registro: 64 zeros. */
    public static final String GENESIS_HASH = "0".repeat(64);

    /** Rótulo e versão do formato canônico. Entra no HMAC (separação de domínio). */
    public static final String FORMAT = "auditgov-chain-v1";

    /**
     * Chave do bloqueio consultivo ({@code pg_advisory_xact_lock}) que serializa a gravação
     * dos registros: "AUDITLOG" em ASCII.
     */
    public static final long LOCK_KEY = 0x41554449544C4F47L;

    private AuditChain() {
        // Classe utilitária.
    }

    /** Campos de um registro da trilha, como entram no cálculo do hash. */
    public record Row(long seq,
                      UUID id,
                      String traceId,
                      String spanId,
                      String operatorId,
                      String action,
                      String resource,
                      String subjectRef,
                      String payloadHash,
                      Instant timestamp,
                      String prevHash,
                      String recordHash) {
    }

    /** Motivo pelo qual a verificação reprovou a cadeia. */
    public enum Failure {
        /** A sequência não é contínua: registro removido, inserido ou fora de ordem. */
        SEQ_GAP,
        /** O {@code prev_hash} não é o {@code record_hash} do registro anterior. */
        PREV_HASH_MISMATCH,
        /** O {@code record_hash} não bate com o conteúdo (conteúdo alterado ou chave diferente). */
        RECORD_HASH_MISMATCH,
        /** O registro âncora existe, mas com hash diferente do ancorado. */
        ANCHOR_MISMATCH,
        /** A cadeia termina antes da âncora: registros finais removidos. */
        TAIL_TRUNCATED
    }

    /** Resultado de uma verificação. {@code failedSeq}, {@code reason} e {@code detail} só existem se inválida. */
    public record Result(boolean valid,
                         long checked,
                         Long headSeq,
                         String headHash,
                         Long failedSeq,
                         Failure reason,
                         String detail) {
    }

    // ------------------------------------------------------------------
    // Cálculo do hash
    // ------------------------------------------------------------------

    /** Microssegundos desde a época Unix; é o que entra no hash e o que o PostgreSQL guarda. */
    public static long epochMicros(Instant instant) {
        Objects.requireNonNull(instant, "carimbo de tempo nulo");
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    }

    /** Serialização canônica do registro (todos os campos, exceto {@code recordHash}). */
    public static byte[] canonical(Row row) {
        String[] fields = {
                FORMAT,
                Long.toString(row.seq()),
                text(row.id(), "id"),
                text(row.traceId(), "trace_id"),
                text(row.spanId(), "span_id"),
                text(row.operatorId(), "operator_id"),
                text(row.action(), "action"),
                text(row.resource(), "resource"),
                row.subjectRef() == null ? "" : row.subjectRef(),
                text(row.payloadHash(), "payload_hash"),
                Long.toString(epochMicros(row.timestamp())),
                text(row.prevHash(), "prev_hash")
        };
        StringBuilder out = new StringBuilder(320);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].indexOf('\n') >= 0 || fields[i].indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Campo com quebra de linha não cabe no formato canônico");
            }
            if (i > 0) {
                out.append('\n');
            }
            out.append(fields[i]);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code record_hash} do registro: HMAC-SHA-256 do formato canônico, em hexadecimal minúsculo. */
    public static String hash(byte[] chainKey, Row row) {
        return HmacUtils.hmacSha256Hex(chainKey, canonical(row));
    }

    private static String text(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Campo obrigatório ausente: " + field);
        }
        return value.toString();
    }

    // ------------------------------------------------------------------
    // Verificação
    // ------------------------------------------------------------------

    /**
     * Verificador em fluxo: recebe os registros em ordem crescente de {@code seq}, um a um, e
     * guarda só o necessário (memória constante). Não é seguro para uso concorrente.
     *
     * Por registro, confere nesta ordem: (1) a sequência é contínua a partir de 1; (2) o
     * {@code prev_hash} é o {@code record_hash} do anterior (o primeiro aponta para
     * {@link #GENESIS_HASH}); (3) o {@code record_hash} é o HMAC do conteúdo. Ao primeiro
     * defeito, para e informa onde.
     */
    public static final class Verifier {

        private final byte[] key;
        private final Long anchorSeq;
        private final String anchorHash;

        private long expectedSeq = 1;
        private String expectedPrev = GENESIS_HASH;
        private long checked;
        private Long headSeq;
        private String headHash;
        private boolean anchorSeen;

        private Long failedSeq;
        private Failure failure;
        private String detail;

        public Verifier(byte[] chainKey) {
            this(chainKey, null, null);
        }

        /**
         * @param anchorSeq  posição de um registro cujo hash foi guardado FORA do banco, ou nulo
         * @param anchorHash o {@code record_hash} guardado para essa posição, ou nulo
         *                   (os dois são informados juntos ou nenhum)
         */
        public Verifier(byte[] chainKey, Long anchorSeq, String anchorHash) {
            if ((anchorSeq == null) != (anchorHash == null)) {
                throw new IllegalArgumentException("Informe a âncora completa (seq e hash) ou nenhuma");
            }
            if (anchorSeq != null && anchorSeq < 1) {
                throw new IllegalArgumentException("A âncora deve ter seq >= 1");
            }
            this.key = Objects.requireNonNull(chainKey, "chave da cadeia nula");
            this.anchorSeq = anchorSeq;
            this.anchorHash = anchorHash;
        }

        /** @return {@code true} se o registro foi aceito e a leitura pode continuar. */
        public boolean accept(Row row) {
            if (failure != null) {
                return false;
            }
            if (row.seq() != expectedSeq) {
                return fail(row.seq(), Failure.SEQ_GAP,
                        "esperado seq=" + expectedSeq + ", encontrado seq=" + row.seq()
                                + ": registro removido, inserido ou fora de ordem");
            }
            if (!sameHash(row.prevHash(), expectedPrev)) {
                return fail(row.seq(), Failure.PREV_HASH_MISMATCH,
                        "prev_hash não é o record_hash do registro anterior");
            }
            String recomputed;
            try {
                recomputed = hash(key, row);
            } catch (RuntimeException e) {
                recomputed = null; // campo ausente ou fora do formato canônico: não pode bater
            }
            if (!sameHash(row.recordHash(), recomputed)) {
                return fail(row.seq(), Failure.RECORD_HASH_MISMATCH,
                        "record_hash não confere com o conteúdo (conteúdo alterado, ou chave HMAC "
                                + "diferente da usada na gravação)");
            }
            if (anchorSeq != null && row.seq() == anchorSeq) {
                if (!sameHash(row.recordHash(), anchorHash)) {
                    return fail(row.seq(), Failure.ANCHOR_MISMATCH,
                            "o record_hash deste registro difere do hash ancorado fora do banco");
                }
                anchorSeen = true;
            }
            checked++;
            headSeq = row.seq();
            headHash = row.recordHash();
            expectedSeq++;
            expectedPrev = row.recordHash();
            return true;
        }

        /** {@code true} depois que algum registro foi reprovado. */
        public boolean failed() {
            return failure != null;
        }

        /** Encerra a verificação (depois do último registro) e devolve o resultado. */
        public Result finish() {
            if (failure == null && anchorSeq != null && !anchorSeen) {
                fail(anchorSeq, Failure.TAIL_TRUNCATED,
                        "a cadeia termina em seq=" + (headSeq == null ? "(vazia)" : headSeq)
                                + ", antes da âncora (seq=" + anchorSeq + "): registros finais removidos");
            }
            return new Result(failure == null, checked, headSeq, headHash, failedSeq, failure, detail);
        }

        private boolean fail(long seq, Failure reason, String message) {
            this.failedSeq = seq;
            this.failure = reason;
            this.detail = message;
            return false;
        }
    }

    /** Comparação em tempo constante; {@code null} nunca é igual a nada. */
    private static boolean sameHash(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
