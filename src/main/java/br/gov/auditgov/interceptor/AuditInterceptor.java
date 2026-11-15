package br.gov.auditgov.interceptor;

import br.gov.auditgov.annotation.Auditable;
import br.gov.auditgov.annotation.AuditSubjectProvider;
import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.AuditCrypto;
import br.gov.auditgov.service.AuditEvidenceStore;
import br.gov.auditgov.service.AuditSettings;
import br.gov.auditgov.util.OperatorIds;
import br.gov.auditgov.util.RouteTemplates;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * Interceptador central do AuditGov.
 *
 * Fluxo por requisição anotada com {@link Auditable}:
 *   1. Resolve o operador a partir da identidade AUTENTICADA (principal do token JWT
 *      já validado pelo framework: assinatura, emissor e validade). Não lê cabeçalho
 *      algum do cliente. Em endpoint público (sem autenticação) o operador é
 *      ANONYMOUS_OPERATOR;
 *   2. Resolve o modelo de rota (nunca o caminho real) e a referência
 *      pseudonimizada do titular (HMAC do CPF), se o método recebe um parâmetro
 *      que implemente {@link AuditSubjectProvider};
 *   3. Lê traceId/spanId do contexto OpenTelemetry ativo (W3C Trace Context) e
 *      enriquece o span com tags de auditoria (visíveis no Jaeger);
 *   4. Executa o método alvo e captura o conteúdo da resposta;
 *   5. Calcula o HMAC-SHA-256 do conteúdo (Art. 6º, III da LGPD: não se grava o
 *      dado, só uma impressão digital com chave);
 *   6. Grava a evidência no PostgreSQL em transação própria. Se a gravação falhar,
 *      o comportamento depende de auditgov.failure-mode: lenient (registra o erro
 *      no log e responde) ou strict (responde 503 e não entrega o dado).
 *
 * A correlação entre o banco e o Jaeger é feita pelo traceId: a mesma string de
 * 32 caracteres hexadecimais aparece na coluna trace_id e na URL do trace.
 */
@Auditable
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class AuditInterceptor {

    private static final Logger LOG = Logger.getLogger(AuditInterceptor.class);

    private static final String UNKNOWN_RESOURCE = "UNKNOWN_RESOURCE";

    @Inject
    Tracer tracer;

    @Inject
    CurrentVertxRequest currentVertxRequest;

    @Inject
    SecurityIdentity identity;

    @Inject
    AuditEvidenceStore evidenceStore;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AuditSettings settings;

    @Inject
    AuditCrypto crypto;

    @AroundInvoke
    public Object audit(InvocationContext context) throws Exception {
        if (!settings.enabled()) {
            // Auditoria desligada por configuração (auditgov.enabled=false).
            return context.proceed();
        }

        final Auditable auditable = resolveAnnotation(context);
        final String action = (auditable != null) ? auditable.action() : "UNSPECIFIED_ACTION";
        final String declaredResource = (auditable != null) ? auditable.resource() : "";
        final HttpServerRequest request = resolveRequest();
        final String operatorId = resolveOperator();
        final String resource = resolveResource(declaredResource, context, request);
        final String subjectRef = crypto.subjectRef(resolveSubject(context));

        // Tags no span ATIVO da requisição: é este span que o Jaeger já exibe na
        // trilha HTTP, então as tags aparecem sem busca adicional.
        Span activeSpan = Span.current();
        activeSpan.setAttribute("audit.operator", operatorId);
        activeSpan.setAttribute("audit.action", action);
        activeSpan.setAttribute("audit.resource", resource);
        if (subjectRef != null) {
            activeSpan.setAttribute("audit.subject_ref", subjectRef);
        }

        // Span filho dedicado: separa visualmente o custo da auditoria do custo da
        // regra de negócio e sobrevive como evidência própria no Jaeger.
        Span auditSpan = tracer.spanBuilder("auditgov.evidence")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("audit.operator", operatorId)
                .setAttribute("audit.action", action)
                .setAttribute("audit.resource", resource)
                .startSpan();
        if (subjectRef != null) {
            auditSpan.setAttribute("audit.subject_ref", subjectRef);
        }

        try (Scope ignored = auditSpan.makeCurrent()) {

            // traceId/spanId do contexto W3C Trace Context corrente.
            SpanContext spanContext = auditSpan.getSpanContext();
            String traceId = spanContext.getTraceId();
            String spanId = spanContext.getSpanId();

            Object result;
            try {
                // Executa o método alvo e captura a resposta.
                result = context.proceed();
            } catch (Exception businessFailure) {
                auditSpan.recordException(businessFailure);
                auditSpan.setStatus(StatusCode.ERROR, "Falha na operação auditada");
                // Acesso tentado também é evento auditável (accountability). Nenhum dado
                // foi entregue, então aqui a falha de gravação nunca troca a exceção original.
                boolean saved = persist(new AuditLog(
                        traceId,
                        spanId,
                        operatorId,
                        action + "_FAILED",
                        resource,
                        subjectRef,
                        crypto.payloadHash(new byte[0]), // sem conteúdo entregue
                        Instant.now()
                ));
                auditSpan.setAttribute("audit.persisted", saved);
                throw businessFailure;
            }

            warnIfAsync(result, action);

            // HMAC-SHA-256 do conteúdo efetivamente devolvido ao operador.
            String payloadHash = hashOf(result);
            auditSpan.setAttribute("audit.payload.hmac", payloadHash);

            // Gravação transacional da evidência.
            boolean saved = persist(new AuditLog(
                    traceId,
                    spanId,
                    operatorId,
                    action,
                    resource,
                    subjectRef,
                    payloadHash,
                    Instant.now()
            ));
            auditSpan.setAttribute("audit.persisted", saved);

            if (!saved) {
                auditSpan.setStatus(StatusCode.ERROR, "Evidência de auditoria não gravada");
                if (settings.strict()) {
                    // Sem registro, sem acesso: o resultado já obtido é descartado.
                    throw new AuditUnavailableException();
                }
            }

            return result;
        } finally {
            auditSpan.end();
        }
    }

    // ------------------------------------------------------------------
    // Resolução de metadados
    // ------------------------------------------------------------------

    /**
     * Lê a anotação do método interceptado. Necessário porque os membros são
     * &#64;Nonbinding e, portanto, não chegam pela binding do CDI.
     * Pode retornar null (tratado com valores padrão pelo chamador).
     */
    private Auditable resolveAnnotation(InvocationContext context) {
        return context.getMethod().getAnnotation(Auditable.class);
    }

    private HttpServerRequest resolveRequest() {
        try {
            var current = currentVertxRequest.getCurrent();
            return current != null ? current.request() : null;
        } catch (RuntimeException e) {
            // Chamada fora de um contexto HTTP (ex.: teste unitário direto do bean).
            return null;
        }
    }

    /**
     * Operador AUTENTICADO: nome do principal do token JWT que o framework já validou
     * (assinatura RS256 com a chave pública configurada, emissor e expiração). Para um
     * JWT, o nome é a claim {@code upn}; se ausente, {@code preferred_username}; se ausente,
     * {@code sub} (MicroProfile JWT). O valor é limpo e limitado a 120 caracteres.
     *
     * Um endpoint que exige autenticação nunca chega aqui sem identidade: o framework
     * responde 401 antes de chamar o método. {@link OperatorIds#ANONYMOUS_OPERATOR} só
     * aparece se {@code @Auditable} for usado em um endpoint público.
     *
     * Limite: o token prova que o chamador possui uma credencial válida, não que a pessoa
     * certa a usou. Um token emprestado continua válido até expirar.
     */
    private String resolveOperator() {
        try {
            if (identity == null || identity.isAnonymous()) {
                return OperatorIds.ANONYMOUS_OPERATOR;
            }
            Principal principal = identity.getPrincipal();
            return OperatorIds.sanitize(principal == null ? null : principal.getName());
        } catch (RuntimeException e) {
            // Sem contexto de segurança ativo (ex.: chamada fora de uma requisição).
            return OperatorIds.ANONYMOUS_OPERATOR;
        }
    }

    /**
     * Recurso acessado: método HTTP + MODELO de rota ({@code @Path}). O caminho real
     * da requisição não é usado, porque pode conter identificadores de pessoas.
     */
    private String resolveResource(String declaredResource, InvocationContext context, HttpServerRequest request) {
        if (declaredResource != null && !declaredResource.isBlank()) {
            return declaredResource;
        }
        String template = RouteTemplates.templateOf(context.getMethod());
        if (template == null) {
            return UNKNOWN_RESOURCE;
        }
        return (request == null) ? template : request.method().name() + " " + template;
    }

    /** Identificador bruto do titular, se algum parâmetro do método declara um. */
    private String resolveSubject(InvocationContext context) {
        Object[] parameters = context.getParameters();
        if (parameters == null) {
            return null;
        }
        for (Object parameter : parameters) {
            if (parameter instanceof AuditSubjectProvider provider) {
                return provider.auditSubjectId();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Conteúdo entregue
    // ------------------------------------------------------------------

    /**
     * Serializa o conteúdo de resposta de forma canônica e devolve seu HMAC-SHA-256.
     * Aceita tanto DTOs quanto {@link Response}, extraindo a entidade.
     */
    private String hashOf(Object result) {
        Object payload = (result instanceof Response response) ? response.getEntity() : result;
        if (payload == null) {
            return crypto.payloadHash(new byte[0]);
        }
        try {
            // Mesmo ObjectMapper usado pelo RESTEasy Reactive: o hash reflete o
            // JSON que o cliente realmente recebeu.
            return crypto.payloadHash(objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            LOG.warnf(e, "Falha ao serializar payload de %s para hash; usando toString()",
                    payload.getClass().getName());
            return crypto.payloadHash(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void warnIfAsync(Object result, String action) {
        if (result instanceof CompletionStage<?> || isMutiny(result)) {
            LOG.warnf("Ação %s retornou tipo reativo; o hash cobre o wrapper, não o item emitido. "
                    + "Use @Blocking no endpoint ou encadeie a auditoria no pipeline reativo.", action);
        }
    }

    private boolean isMutiny(Object result) {
        return result != null && result.getClass().getName().startsWith("io.smallrye.mutiny.");
    }

    // ------------------------------------------------------------------
    // Persistência
    // ------------------------------------------------------------------

    /**
     * Tenta gravar a evidência e informa se conseguiu. Nunca lança exceção: quem
     * decide o que fazer com a falha é o chamador, conforme auditgov.failure-mode.
     *
     * No modo lenient (padrão) a falha só é registrada no log em nível ERROR e o dado
     * é entregue sem evidência correspondente. Há, portanto, perda de evidência sem
     * aviso ao usuário; o span recebe audit.persisted=false para tornar a perda
     * visível no Jaeger. No modo strict o chamador responde 503 e descarta o dado.
     */
    private boolean persist(AuditLog auditLog) {
        try {
            evidenceStore.register(auditLog);
            return true;
        } catch (RuntimeException e) {
            LOG.errorf(e, "FALHA AO GRAVAR EVIDÊNCIA DE AUDITORIA: %s", auditLog);
            return false;
        }
    }
}
