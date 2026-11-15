package br.gov.auditgov.interceptor;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.AuditEvidenceStore;
import io.quarkus.test.junit.QuarkusMock;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modo estrito (auditgov.failure-mode=strict): "sem registro, sem acesso". Se a
 * evidência não puder ser gravada, a resposta é 503 e o dado pessoal não é entregue.
 */
@QuarkusTest
@TestProfile(FailureModeStrictTest.PerfilEstrito.class)
class FailureModeStrictTest {

    public static class PerfilEstrito implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("auditgov.failure-mode", "strict");
        }
    }

    @Test
    void deveNegarOAcessoQuandoAEvidenciaNaoPodeSerGravada() {
        QuarkusMock.installMockForType(new BancoIndisponivel(), AuditEvidenceStore.class);
        String operador = operador("fiscal");

        String corpoDaResposta = given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(503)
                .extract().asString();

        assertFalse(corpoDaResposta.contains("Joana"), "o nome do cidadão não pode ser entregue");
        assertFalse(corpoDaResposta.contains(CPF_EXISTENTE), "o CPF não pode ser entregue");
        assertTrue(AuditLog.findByOperator(operador).isEmpty());
    }

    @Test
    void deveManterOFluxoNormalQuandoOBancoEstaDisponivel() {
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

        assertEquals(1, AuditLog.findByOperator(operador).size());
    }
}
