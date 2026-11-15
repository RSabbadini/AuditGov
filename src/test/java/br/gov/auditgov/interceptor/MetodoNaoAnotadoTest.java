package br.gov.auditgov.interceptor;

import br.gov.auditgov.domain.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.operador;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A auditoria é declarativa: só os métodos marcados com {@code @Auditable} geram
 * evidência. Um método sem a anotação responde normalmente, mesmo para um operador
 * autenticado, e não deixa registro.
 */
@QuarkusTest
class MetodoNaoAnotadoTest {

    @Test
    void naoDeveAuditarMetodoSemAnotacao() {
        String operador = operador("fiscal");

        given()
                .header(AUTORIZACAO, bearer(operador))
        .when()
                .get("/teste/sem-anotacao")
        .then()
                .statusCode(200)
                .body(equalTo("pong"));

        assertTrue(AuditLog.findByOperator(operador).isEmpty(),
                "um método sem @Auditable não deve gerar evidência");
    }
}
