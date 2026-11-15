package br.gov.auditgov.annotation;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um endpoint REST como sujeito à trilha de auditoria do AuditGov.
 *
 * Exemplo:
 * <pre>
 * &#64;GET
 * &#64;Auditable(action = "READ_CITIZEN_DATA")
 * public CitizenDTO buscar(String cpf) { ... }
 * </pre>
 *
 * Detalhe crítico de CDI: os membros de um &#64;InterceptorBinding fazem parte
 * da resolução do interceptador. Sem &#64;Nonbinding, cada valor distinto de
 * "action" geraria uma binding diferente e o AuditInterceptor não seria
 * aplicado. Por isso ambos os membros são &#64;Nonbinding e são lidos em
 * runtime via reflexão no InvocationContext.
 */
@InterceptorBinding
@Inherited
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Auditable {

    /** Nome da ação de negócio registrada na evidência (ex.: READ_CITIZEN_DATA). */
    @Nonbinding
    String action() default "UNSPECIFIED_ACTION";

    /**
     * Identificação opcional do recurso. Se vazio, o interceptador deriva
     * automaticamente "MÉTODO_HTTP modelo-de-rota" a partir das anotações @Path
     * (por exemplo, "POST /api/v1/cidadaos/consulta"). O caminho real da
     * requisição nunca é usado, porque pode conter identificadores de pessoas.
     */
    @Nonbinding
    String resource() default "";
}
