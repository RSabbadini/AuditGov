package br.gov.auditgov.service;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Configurações de comportamento do AuditGov.
 *
 * <ul>
 *   <li>{@code auditgov.enabled}: liga ou desliga o interceptador (útil para
 *       comparar desempenho com e sem auditoria); padrão {@code true}.</li>
 *   <li>{@code auditgov.failure-mode}: {@code lenient} (a falha ao gravar a
 *       evidência é registrada no log e a resposta segue) ou {@code strict} (sem
 *       evidência, sem acesso: a resposta é 503 e o dado não é entregue).</li>
 *   <li>{@code auditgov.append-only.enabled}: instala no banco o gatilho que
 *       bloqueia UPDATE, DELETE e TRUNCATE em audit_log; padrão {@code true}.</li>
 * </ul>
 */
@Startup
@ApplicationScoped
public class AuditSettings {

    @ConfigProperty(name = "auditgov.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "auditgov.failure-mode", defaultValue = "lenient")
    String failureMode;

    @ConfigProperty(name = "auditgov.append-only.enabled", defaultValue = "true")
    boolean appendOnlyGuard;

    @PostConstruct
    void validate() {
        String mode = failureMode == null ? "" : failureMode.strip();
        if (!mode.equalsIgnoreCase("lenient") && !mode.equalsIgnoreCase("strict")) {
            throw new IllegalStateException(
                    "auditgov.failure-mode deve ser 'lenient' ou 'strict', mas é '" + failureMode + "'");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** {@code true} quando uma falha ao gravar a evidência deve impedir a resposta. */
    public boolean strict() {
        return failureMode.strip().equalsIgnoreCase("strict");
    }

    public boolean appendOnlyGuard() {
        return appendOnlyGuard;
    }
}
