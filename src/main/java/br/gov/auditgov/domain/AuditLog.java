package br.gov.auditgov.domain;

import br.gov.auditgov.util.AuditChain;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Evidência de auditoria (somente-anexo e encadeada).
 *
 * LGPD - Art. 37 (registro das operações de tratamento) e Art. 46 (segurança):
 * cada leitura de dado pessoal gera um registro próprio, correlacionável ao
 * rastro distribuído (W3C Trace Context) e ao operador autenticado.
 *
 * Proteção contra alteração, em três camadas:
 * (1) na aplicação, {@code @Immutable} e colunas {@code updatable = false} fazem o
 *     Hibernate nunca emitir UPDATE;
 * (2) no banco, o gatilho instalado por AuditSchemaGuard (db/audit-append-only.sql)
 *     rejeita UPDATE, DELETE e TRUNCATE vindos de qualquer cliente SQL (o dono da tabela
 *     e o superusuário ainda podem removê-lo; veja db/audit-app-role.sql);
 * (3) no conteúdo, cada registro guarda o hash do anterior ({@link #prevHash}) e o seu
 *     próprio ({@link #recordHash}, um HMAC com chave fora do banco). A camada (3) não
 *     impede a alteração: faz com que ela seja DETECTÁVEL (veja {@link AuditChain}).
 *
 * LGPD - Art. 6º, III (necessidade): nenhum dado do titular é gravado em claro.
 * O titular aparece apenas como {@code subjectRef} (HMAC-SHA-256 com chave fora do
 * banco, dado pseudonimizado) e o conteúdo entregue como {@code payloadHash} (HMAC).
 */
@Entity
@Immutable // Hibernate nunca emitirá UPDATE para esta entidade.
@Table(
        name = "audit_log",
        indexes = {
                @Index(name = "idx_audit_trace_id", columnList = "trace_id"),
                @Index(name = "idx_audit_operator", columnList = "operator_id"),
                @Index(name = "idx_audit_timestamp", columnList = "event_timestamp"),
                @Index(name = "idx_audit_subject_ref", columnList = "subject_ref")
        }
)
public class AuditLog extends PanacheEntityBase {

    /**
     * Identificador do registro. É atribuído pela aplicação (UUID aleatório) ANTES do
     * cálculo do hash, porque o id entra no hash da cadeia.
     */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    public UUID id;

    /**
     * Posição na cadeia: 1, 2, 3, ... sem buracos. Atribuída por AuditLogService sob o
     * bloqueio que serializa as gravações (não é uma SEQUENCE do banco, que admite buracos
     * e não garante a ordem de confirmação). A restrição de unicidade impede dois registros
     * na mesma posição.
     */
    @Column(name = "seq", nullable = false, updatable = false, unique = true)
    public long seq;

    /** Trace ID de 32 hex chars do W3C Trace Context, chave de correlação com o Jaeger. */
    @Column(name = "trace_id", length = 32, nullable = false, updatable = false)
    public String traceId;

    /** Span ID de 16 hex chars, identifica a operação exata dentro do trace. */
    @Column(name = "span_id", length = 16, nullable = false, updatable = false)
    public String spanId;

    /**
     * Operador AUTENTICADO: nome do principal do token JWT validado (claim {@code upn}; se
     * ausente, {@code preferred_username}; se ausente, {@code sub}), limpo e limitado a 120
     * caracteres. O valor vem da assinatura do token, não de um cabeçalho livre. Ressalva: o
     * token prova que o chamador possui uma credencial válida, não que a pessoa certa a usou
     * (um token emprestado continua válido até expirar).
     */
    @Column(name = "operator_id", length = 120, nullable = false, updatable = false)
    public String operatorId;

    /** Ação de negócio auditada, ex.: READ_CITIZEN_DATA. */
    @Column(name = "action", length = 80, nullable = false, updatable = false)
    public String action;

    /** Modelo de rota acessado, ex.: POST /api/v1/cidadaos/consulta (nunca o caminho real). */
    @Column(name = "resource", length = 500, nullable = false, updatable = false)
    public String resource;

    /**
     * Referência pseudonimizada do titular consultado: HMAC-SHA-256 (64 hex) do CPF.
     * Nulo quando a requisição não identifica um titular (por exemplo, CPF ausente).
     */
    @Column(name = "subject_ref", length = 64, updatable = false)
    public String subjectRef;

    /** HMAC-SHA-256 hexadecimal (64 chars) do conteúdo devolvido ao operador. */
    @Column(name = "payload_hash", length = 64, nullable = false, updatable = false)
    public String payloadHash;

    /** Momento do evento em UTC, com precisão de microssegundos (a da coluna timestamptz(6)). */
    @Column(name = "event_timestamp", nullable = false, updatable = false)
    public Instant timestamp;

    /** {@code record_hash} do registro anterior; 64 zeros no primeiro registro. */
    @Column(name = "prev_hash", length = 64, nullable = false, updatable = false)
    public String prevHash;

    /** HMAC-SHA-256 (64 hex) do formato canônico deste registro, incluindo {@link #prevHash}. */
    @Column(name = "record_hash", length = 64, nullable = false, updatable = false)
    public String recordHash;

    public AuditLog() {
        // Exigido pelo JPA.
    }

    /**
     * Cria o registro ainda SEM posição na cadeia: {@code id}, {@code seq}, {@code prevHash} e
     * {@code recordHash} são preenchidos por AuditLogService no momento da gravação.
     */
    public AuditLog(String traceId,
                    String spanId,
                    String operatorId,
                    String action,
                    String resource,
                    String subjectRef,
                    String payloadHash,
                    Instant timestamp) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.operatorId = operatorId;
        this.action = action;
        this.resource = resource;
        this.subjectRef = subjectRef;
        this.payloadHash = payloadHash;
        // O PostgreSQL guarda microssegundos; o que é hasheado tem de ser o que fica gravado.
        this.timestamp = timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.MICROS);
    }

    /** Campos do registro na forma usada pelo cálculo e pela verificação da cadeia. */
    public AuditChain.Row toChainRow() {
        return new AuditChain.Row(seq, id, traceId, spanId, operatorId, action, resource,
                subjectRef, payloadHash, timestamp, prevHash, recordHash);
    }

    // --- Consultas de apoio (Active Record do Panache) ---

    public static java.util.List<AuditLog> findByTraceId(String traceId) {
        return list("traceId", traceId);
    }

    public static java.util.List<AuditLog> findByOperator(String operatorId) {
        return list("operatorId = ?1 order by timestamp desc", operatorId);
    }

    /** Acessos aos dados de um titular (direito de acesso, LGPD Art. 18), a partir da referência HMAC. */
    public static java.util.List<AuditLog> findBySubjectRef(String subjectRef) {
        return list("subjectRef = ?1 order by timestamp desc", subjectRef);
    }

    @Override
    public String toString() {
        return ("AuditLog{id=%s, seq=%d, traceId='%s', spanId='%s', operatorId='%s', action='%s', resource='%s', "
                + "subjectRef='%s', payloadHash='%s', timestamp=%s, prevHash='%s', recordHash='%s'}")
                .formatted(id, seq, traceId, spanId, operatorId, action, resource, subjectRef, payloadHash,
                        timestamp, prevHash, recordHash);
    }
}
