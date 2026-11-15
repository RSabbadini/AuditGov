package br.gov.auditgov.facade;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static br.gov.auditgov.facade.ApoioTeste.CPF_EXISTENTE;
import static br.gov.auditgov.facade.ApoioTeste.ROTA;
import static br.gov.auditgov.facade.ApoioTeste.corpoCpf;
import static br.gov.auditgov.facade.ApoioTeste.spanIdAleatorio;
import static br.gov.auditgov.facade.ApoioTeste.traceIdAleatorio;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que a fachada ENVIA ao serviço de cidadãos e como trata a resposta, com a propagação
 * automática do Quarkus (padrão).
 *
 * Se o primeiro teste falhar com "o serviço de cidadãos deveria ter recebido o traceparent",
 * a instrumentação automática do cliente REST não injetou o cabeçalho nesta versão: ligue
 * facade.trace.manual-propagation=true (veja PropagacaoManualTest, que prova esse caminho).
 *
 * Estes testes provam o COMPORTAMENTO DA FACHADA contra um serviço falso. A prova com os dois
 * serviços verdadeiros e o Jaeger é o roteiro scripts/e2e-trace.sh.
 */
@QuarkusTest
class PropagacaoTest {

    private static CidadaosFalso cidadaos;

    @BeforeAll
    static void subirCidadaosFalso() throws IOException {
        cidadaos = new CidadaosFalso();
    }

    @AfterAll
    static void pararCidadaosFalso() {
        if (cidadaos != null) {
            cidadaos.close();
        }
    }

    @BeforeEach
    void limpar() {
        cidadaos.limpar();
    }

    // ------------------------------------------------------------------
    // Propagação do contexto de rastreamento
    // ------------------------------------------------------------------

    @Test
    void deveRepassarOMesmoTraceIdAoServicoDeCidadaos() {
        String traceId = traceIdAleatorio();
        String spanIdDoCliente = spanIdAleatorio();

        given()
                .header("traceparent", "00-" + traceId + "-" + spanIdDoCliente + "-01")
                .header("Authorization", "Bearer token-de-teste")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA)
        .then()
                .statusCode(200);

        String recebido = cidadaos.unica().cabecalho("traceparent");
        assertNotNull(recebido, "o serviço de cidadãos deveria ter recebido o traceparent");
        assertTrue(recebido.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]"), "formato W3C: " + recebido);
        assertEquals(traceId, recebido.substring(3, 35),
                "o serviço de cidadãos deve continuar o MESMO trace da requisição que entrou na fachada");
        assertNotEquals(spanIdDoCliente, recebido.substring(36, 52),
                "o pai do span chamado deve ser um span da FACHADA, não o span do cliente original");
    }

    // ------------------------------------------------------------------
    // O que é encaminhado
    // ------------------------------------------------------------------

    @Test
    void deveRepassarOAuthorizationSemAlterar() {
        String autorizacao = "Bearer cabecalho.corpo.assinatura";

        given()
                .header("Authorization", autorizacao)
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA)
        .then()
                .statusCode(200);

        assertEquals(autorizacao, cidadaos.unica().cabecalho("authorization"));
    }

    @Test
    void deveEnviarOCpfSoNoCorpoENuncaNaUrl() {
        given()
                .header("Authorization", "Bearer token-de-teste")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA)
        .then()
                .statusCode(200);

        CidadaosFalso.Recebida recebida = cidadaos.unica();
        assertEquals("POST", recebida.metodo());
        assertEquals("/api/v1/cidadaos/consulta", recebida.caminho());
        assertFalse(recebida.caminho().contains(CPF_EXISTENTE), "o CPF não pode ir na URL");
        assertTrue(recebida.corpo().contains(CPF_EXISTENTE), "o CPF vai no corpo");
    }

    @Test
    void naoDeveRepassarXUserId() {
        given()
                .header("Authorization", "Bearer token-de-teste")
                .header("X-User-ID", "fantasma")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA)
        .then()
                .statusCode(200);

        assertNull(cidadaos.unica().cabecalho("x-user-id"), "a identidade vem só do token");
    }

    // ------------------------------------------------------------------
    // O que volta
    // ------------------------------------------------------------------

    @Test
    void deveDevolverOCidadaoRecebidoDoServicoDeCidadaos() {
        given()
                .header("Authorization", "Bearer token-de-teste")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA)
        .then()
                .statusCode(200)
                .body("nome", equalTo(CidadaosFalso.NOME));
    }

    @Test
    void deveRepassarOStatus401QuandoNaoHaAuthorization() {
        given()
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA)
        .then()
                .statusCode(401);

        assertNull(cidadaos.unica().cabecalho("authorization"),
                "sem Authorization na entrada, nada de Authorization na chamada ao serviço de cidadãos");
    }

    @Test
    void deveRepassarOStatus404SemOCorpoDeErroDoServicoDeCidadaos() {
        String corpo = given()
                .header("Authorization", "Bearer token-de-teste")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CidadaosFalso.CPF_NAO_ENCONTRADO))
        .when()
                .post(ROTA)
        .then()
                .statusCode(404)
                .extract().asString();

        assertFalse(corpo.contains(CidadaosFalso.DETALHE_INTERNO),
                "a fachada repassa só o status, nunca o corpo de erro do serviço de cidadãos");
    }

    @Test
    void deveRepassarOStatus503DoServicoDeCidadaos() {
        given()
                .header("Authorization", "Bearer token-de-teste")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CidadaosFalso.CPF_QUE_FALHA))
        .when()
                .post(ROTA)
        .then()
                .statusCode(503);
    }

    @Test
    void deveResponder400SemChamarOServicoDeCidadaosQuandoOCpfFaltar() {
        given()
                .header("Authorization", "Bearer token-de-teste")
                .contentType(ContentType.JSON)
                .body("{}")
        .when()
                .post(ROTA)
        .then()
                .statusCode(400);

        assertTrue(cidadaos.recebidas().isEmpty(), "sem CPF, a fachada nem chama o serviço de cidadãos");
    }
}
