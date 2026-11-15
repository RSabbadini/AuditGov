package br.gov.auditgov.service;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Instala no PostgreSQL a proteção que torna a tabela audit_log somente-anexo.
 *
 * Roda no início da aplicação, depois de o Hibernate criar ou atualizar a
 * tabela, e executa o arquivo db/audit-append-only.sql (gatilhos que rejeitam
 * UPDATE, DELETE e TRUNCATE). É idempotente. Se a proteção não puder ser
 * instalada, a aplicação NÃO sobe: uma trilha que se diz protegida mas não está
 * seria pior do que nenhuma promessa.
 *
 * Limite: o dono da tabela e o superusuário ainda conseguem remover o gatilho.
 * Para fechar essa lacuna, a aplicação deve rodar com um papel sem esses
 * privilégios (db/audit-app-role.sql).
 */
@ApplicationScoped
public class AuditSchemaGuard {

    static final String SCRIPT = "db/audit-append-only.sql";
    private static final Logger LOG = Logger.getLogger(AuditSchemaGuard.class);

    @Inject
    DataSource dataSource;

    @Inject
    AuditSettings settings;

    void onStart(@Observes StartupEvent event) {
        if (!settings.appendOnlyGuard()) {
            LOG.warn("auditgov.append-only.enabled=false: a tabela audit_log NÃO está protegida "
                    + "contra UPDATE, DELETE e TRUNCATE.");
            return;
        }
        install();
    }

    void install() {
        List<String> statements = loadStatements();
        try (Connection connection = dataSource.getConnection()) {
            if (!tableExists(connection)) {
                throw new IllegalStateException(
                        "A tabela audit_log não existe. Crie-a antes (por exemplo, com "
                                + "quarkus.hibernate-orm.database.generation=update) para instalar a proteção.");
            }
            for (String sql : statements) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao instalar a proteção append-only em audit_log", e);
        }
        LOG.info("Proteção append-only instalada em audit_log (UPDATE, DELETE e TRUNCATE bloqueados por gatilho).");
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT to_regclass('audit_log') IS NOT NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    static List<String> loadStatements() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = AuditSchemaGuard.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(SCRIPT)) {
            if (in == null) {
                throw new IllegalStateException("Arquivo " + SCRIPT + " não encontrado no classpath");
            }
            return split(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível ler " + SCRIPT, e);
        }
    }

    /**
     * Separa o script em comandos: cada bloco de texto delimitado por linhas em
     * branco é um comando. Linhas de comentário (--) são descartadas e blocos que
     * só têm comentários são ignorados.
     */
    static List<String> split(String script) {
        List<String> statements = new ArrayList<>();
        for (String block : script.split("(\\r?\\n)[ \\t]*(\\r?\\n)")) {
            StringBuilder sql = new StringBuilder();
            for (String line : block.split("\\r?\\n")) {
                if (!line.stripLeading().startsWith("--")) {
                    sql.append(line).append('\n');
                }
            }
            String statement = sql.toString().strip();
            if (!statement.isEmpty()) {
                statements.add(statement);
            }
        }
        return statements;
    }
}
