package br.gov.auditgov.interceptor;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.AuditEvidenceStore;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_CIDADAOS;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.operador;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modo padrão (auditgov.failure-mode=lenient): documenta, com um teste, o
 * comportamento quando o banco falha. O dado é entregue e a evidência se perde,
 * apenas com registro de erro no log. É uma limitação conhecida; o modo strict
 * (FailureModeStrictTest) existe para quem não aceita essa perda.
 */
@QuarkusTest
class FailureModeLenientTest {

    @Test
    void deveEntregarODadoESemEvidenciaQuandoOBancoFalha() {
        QuarkusMock.installMockForType(new BancoIndisponivel(), AuditEvidenceStore.class);
        String operador = operador("fiscal");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200)
                .body("nome", notNullValue());

        assertTrue(AuditLog.findByOperator(operador).isEmpty(),
                "no modo lenient, sem banco não há evidência (perda silenciosa documentada)");
    }
}
