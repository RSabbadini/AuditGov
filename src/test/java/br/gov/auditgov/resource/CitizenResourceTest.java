package br.gov.auditgov.resource;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.AuditCrypto;
import br.gov.auditgov.util.OperatorIds;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.CPF_INEXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_CIDADAOS;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.operador;
import static br.gov.auditgov.TestSupport.spanIdAleatorio;
import static br.gov.auditgov.TestSupport.traceIdAleatorio;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testes de integração: sobem o Quarkus completo (CDI real, interceptador real).
 * Requerem o PostgreSQL do docker-compose e o banco auditgov_test (ver
 * src/test/resources/application.properties).
 *
 * Todas as chamadas levam um token JWT assinado (TestSupport.bearer): o operador gravado na
 * trilha é o upn do token. A recusa de requisições sem token válido é testada em
 * AutenticacaoTest.
 *
 * Os testes não apagam linhas (a tabela é append-only): cada um usa um operador
 * único e consulta só o que criou.
 */
@QuarkusTest
class CitizenResourceTest {

    @Inject
    AuditCrypto crypto;

    @Test
    void deveRetornarCidadaoEGravarEvidenciaDeAuditoria() {
        String operador = operador("fiscal");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .accept(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200)
                .body("cpf", equalTo(CPF_EXISTENTE))
                .body("nome", notNullValue());

        List<AuditLog> registros = AuditLog.findByOperator(operador);
        assertEquals(1, registros.size(), "deveria existir exatamente uma evidência gravada");

        AuditLog evidencia = registros.get(0);
        assertEquals("READ_CITIZEN_DATA", evidencia.action);
        assertEquals(32, evidencia.traceId.length(), "traceId W3C deve ter 32 chars hex");
        assertEquals(16, evidencia.spanId.length(), "spanId W3C deve ter 16 chars hex");
        assertEquals(64, evidencia.payloadHash.length(), "HMAC-SHA-256 hex deve ter 64 chars");
    }

    @Test
    void deveAuditarTentativaFalhaQuandoCidadaoNaoExiste() {
        String operador = operador("fiscal");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_INEXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(404);

        List<AuditLog> registros = AuditLog.findByOperator(operador);
        assertEquals(1, registros.size());
        assertEquals("READ_CITIZEN_DATA_FAILED", registros.get(0).action,
                "uma tentativa de acesso a CPF inexistente também deve gerar evidência");
        assertEquals(crypto.subjectRef(CPF_INEXISTENTE), registros.get(0).subjectRef,
                "a tentativa mira um titular, mesmo sem resultado");
        assertEquals(crypto.payloadHash(new byte[0]), registros.get(0).payloadHash,
                "nenhum conteúdo foi entregue: o HMAC registrado é o do conteúdo vazio");
    }

    @Test
    void naoDeveGravarCpfEmClaroNaTrilha() {
        String operador = operador("fiscal");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200);

        AuditLog evidencia = AuditLog.findByOperator(operador).get(0);

        List<String> campos = Arrays.asList(evidencia.traceId, evidencia.spanId, evidencia.operatorId,
                evidencia.action, evidencia.resource, evidencia.subjectRef, evidencia.payloadHash);
        for (String campo : campos) {
            assertNotNull(campo);
            assertFalse(campo.contains(CPF_EXISTENTE), "CPF em claro na trilha: " + campo);
        }
        assertEquals("POST /api/v1/cidadaos/consulta", evidencia.resource,
                "resource deve ser o modelo de rota, sem identificadores");
        assertEquals(crypto.subjectRef(CPF_EXISTENTE), evidencia.subjectRef);
        assertNotEquals(CPF_EXISTENTE, evidencia.subjectRef);

        // Direito de acesso do titular (LGPD, Art. 18): consulta pela referência HMAC do CPF.
        boolean achouPeloTitular = AuditLog.findBySubjectRef(crypto.subjectRef(CPF_EXISTENTE)).stream()
                .anyMatch(r -> r.operatorId.equals(operador));
        assertTrue(achouPeloTitular, "deveria ser possível localizar o acesso pela referência do titular");
    }

    @Test
    void deveRejeitarCpfForaDoFormatoEAuditarATentativa() {
        String operador = operador("fiscal");

        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf("abc"))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(400);

        List<AuditLog> registros = AuditLog.findByOperator(operador);
        assertEquals(1, registros.size());
        assertEquals("READ_CITIZEN_DATA_FAILED", registros.get(0).action);
        assertNull(registros.get(0).subjectRef, "sem dígitos não há titular a referenciar");
    }

    @Test
    void deveLimitarOperadorMuitoLongoSemPerderAEvidencia() {
        String longo = operador("op") + "x".repeat(300);
        String esperado = longo.substring(0, OperatorIds.MAX_LENGTH);

        given()
                .header(AUTORIZACAO, bearer(longo))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200);

        assertEquals(1, AuditLog.findByOperator(esperado).size(),
                "o valor deve ser truncado a 120 caracteres e a evidência deve ser gravada");
    }

    @Test
    void devePropagarOTraceparentRecebido() {
        String operador = operador("fiscal");
        String traceId = traceIdAleatorio();

        given()
                .header(AUTORIZACAO, bearer(operador))
                .header("traceparent", "00-" + traceId + "-" + spanIdAleatorio() + "-01")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200);

        List<AuditLog> registros = AuditLog.findByTraceId(traceId);
        assertEquals(1, registros.size(), "o trace_id gravado deve ser o enviado no traceparent");
        assertEquals(operador, registros.get(0).operatorId);
    }

    @Test
    void deveGerarMesmoHashParaMesmoConteudoEOutroParaConteudoDiferente() {
        String operadorA = operador("fiscal");
        String operadorB = operador("fiscal");

        for (int i = 0; i < 2; i++) {
            given().header(AUTORIZACAO, bearer(operadorA)).contentType(ContentType.JSON)
                    .body(corpoCpf(CPF_EXISTENTE))
            .when().post(ROTA_CIDADAOS)
            .then().statusCode(200);
        }
        given().header(AUTORIZACAO, bearer(operadorB)).contentType(ContentType.JSON)
                .body(corpoCpf("98765432100"))
        .when().post(ROTA_CIDADAOS)
        .then().statusCode(200);

        List<AuditLog> deA = AuditLog.findByOperator(operadorA);
        List<AuditLog> deB = AuditLog.findByOperator(operadorB);
        assertEquals(2, deA.size());
        assertEquals(1, deB.size());
        assertEquals(deA.get(0).payloadHash, deA.get(1).payloadHash, "mesmo conteúdo, mesmo HMAC");
        assertNotEquals(deA.get(0).payloadHash, deB.get(0).payloadHash, "conteúdo diferente, HMAC diferente");
    }

    @Test
    void deveAceitarCorpoGrandeSemAlterarAEvidencia() {
        String referencia = operador("fiscal");
        String operador = operador("fiscal");
        // JSON válido com 200 mil espaços em branco: corpo grande, mesmo CPF.
        String corpoGrande = "{\"cpf\":\"" + CPF_EXISTENTE + "\"" + " ".repeat(200_000) + "}";

        given().header(AUTORIZACAO, bearer(referencia)).contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when().post(ROTA_CIDADAOS)
        .then().statusCode(200);

        given().header(AUTORIZACAO, bearer(operador)).contentType(ContentType.JSON)
                .body(corpoGrande)
        .when().post(ROTA_CIDADAOS)
        .then().statusCode(200);

        AuditLog normal = AuditLog.findByOperator(referencia).get(0);
        AuditLog comCorpoGrande = AuditLog.findByOperator(operador).get(0);
        assertEquals(normal.payloadHash, comCorpoGrande.payloadHash,
                "o HMAC cobre o conteúdo entregue, não o tamanho da requisição");
        assertEquals(normal.resource, comCorpoGrande.resource);
        assertEquals(normal.subjectRef, comCorpoGrande.subjectRef);
    }

    @Test
    void deveGravarOperadorComCaracteresEspeciaisComoTexto() {
        // Aspas, ponto e vírgula e comentário SQL devem ser tratados como texto comum.
        String operador = operador("ana") + "'); DROP TABLE audit_log; --";

        given().header(AUTORIZACAO, bearer(operador)).contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when().post(ROTA_CIDADAOS)
        .then().statusCode(200);

        List<AuditLog> registros = AuditLog.findByOperator(operador);
        assertEquals(1, registros.size(), "o valor deve ser gravado literalmente e a tabela continua íntegra");
        assertEquals(operador, registros.get(0).operatorId);
    }
}
