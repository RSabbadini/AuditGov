package br.gov.auditgov.domain;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_CIDADAOS;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.operador;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova que a proteção contra alteração existe no BANCO, e não só na aplicação:
 * comandos SQL enviados direto ao PostgreSQL (o mesmo que faria alguém com acesso
 * ao banco) são rejeitados pelo gatilho instalado por AuditSchemaGuard.
 *
 * Limite: o dono da tabela ainda pode remover o gatilho (veja db/audit-app-role.sql).
 */
@QuarkusTest
class ImmutabilityTest {

    /** SQLSTATE 23001 (restrict_violation), usado pelo gatilho. */
    private static final String SQLSTATE_BLOQUEADO = "23001";

    @Inject
    DataSource dataSource;

    private String criarEvidencia() {
        String operador = operador("imutavel");
        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200);
        assertEquals(1, AuditLog.findByOperator(operador).size());
        return operador;
    }

    private void executar(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    @Test
    void deveBloquearUpdateDiretoNoBanco() {
        String operador = criarEvidencia();

        SQLException erro = assertThrows(SQLException.class, () ->
                executar("UPDATE audit_log SET operator_id = 'adulterado' WHERE operator_id = '" + operador + "'"));

        assertEquals(SQLSTATE_BLOQUEADO, erro.getSQLState());
        assertEquals(1, AuditLog.findByOperator(operador).size(), "a linha original deve continuar intacta");
        assertTrue(AuditLog.findByOperator("adulterado").isEmpty());
    }

    @Test
    void deveBloquearDeleteDiretoNoBanco() {
        String operador = criarEvidencia();

        SQLException erro = assertThrows(SQLException.class, () ->
                executar("DELETE FROM audit_log WHERE operator_id = '" + operador + "'"));

        assertEquals(SQLSTATE_BLOQUEADO, erro.getSQLState());
        assertEquals(1, AuditLog.findByOperator(operador).size(), "a linha deve continuar existindo");
    }

    @Test
    void deveBloquearTruncateDiretoNoBanco() {
        String operador = criarEvidencia();

        SQLException erro = assertThrows(SQLException.class, () -> executar("TRUNCATE TABLE audit_log"));

        assertEquals(SQLSTATE_BLOQUEADO, erro.getSQLState());
        assertEquals(1, AuditLog.findByOperator(operador).size(), "a tabela não pode ser esvaziada");
    }
}
