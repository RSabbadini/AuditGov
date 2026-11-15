package br.gov.auditgov.interceptor;

import br.gov.auditgov.domain.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_CIDADAOS;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.operador;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Com auditgov.enabled=false o interceptador apenas repassa a chamada. Esse é o
 * modo usado como linha de base nas medições de desempenho (bench/README.md).
 */
@QuarkusTest
@TestProfile(AuditDisabledTest.PerfilSemAuditoria.class)
class AuditDisabledTest {

    public static class PerfilSemAuditoria implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("auditgov.enabled", "false");
        }
    }

    @Test
    void deveResponderNormalmenteSemGravarEvidencia() {
        String operador = operador("fiscal");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200)
                .body("cpf", equalTo(CPF_EXISTENTE));

        assertTrue(AuditLog.findByOperator(operador).isEmpty(), "com a auditoria desligada nada é gravado");
    }
}
