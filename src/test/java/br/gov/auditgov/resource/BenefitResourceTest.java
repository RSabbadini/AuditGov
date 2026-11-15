package br.gov.auditgov.resource;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.AuditCrypto;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.CPF_INEXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_BENEFICIOS;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.operador;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Segundo endpoint auditado: confirma que a mesma anotação funciona em outro
 * recurso, sem nenhum código de auditoria em BenefitResource.
 */
@QuarkusTest
class BenefitResourceTest {

    @Inject
    AuditCrypto crypto;

    @Test
    void deveAuditarConsultaDeBeneficioComAcaoPropria() {
        String operador = operador("analista");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_BENEFICIOS)
        .then()
                .statusCode(200)
                .body("situacao", equalTo("ATIVO"));

        List<AuditLog> registros = AuditLog.findByOperator(operador);
        assertEquals(1, registros.size());
        AuditLog evidencia = registros.get(0);
        assertEquals("READ_BENEFIT_DATA", evidencia.action);
        assertEquals("POST /api/v1/beneficios/consulta", evidencia.resource);
        assertEquals(crypto.subjectRef(CPF_EXISTENTE), evidencia.subjectRef);
    }

    @Test
    void deveAuditarTentativaFalhaQuandoNaoHaBeneficio() {
        String operador = operador("analista");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_INEXISTENTE))
        .when()
                .post(ROTA_BENEFICIOS)
        .then()
                .statusCode(404);

        List<AuditLog> registros = AuditLog.findByOperator(operador);
        assertEquals(1, registros.size());
        assertEquals("READ_BENEFIT_DATA_FAILED", registros.get(0).action);
    }
}
