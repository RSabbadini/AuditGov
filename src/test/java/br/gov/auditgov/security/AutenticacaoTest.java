package br.gov.auditgov.security;

import br.gov.auditgov.domain.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_BENEFICIOS;
import static br.gov.auditgov.TestSupport.ROTA_CIDADAOS;
import static br.gov.auditgov.TestSupport.ROTA_VERIFICACAO_CADEIA;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.bearerAuditor;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.operador;
import static br.gov.auditgov.TestSupport.spanIdAleatorio;
import static br.gov.auditgov.TestSupport.traceIdAleatorio;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O operador gravado na trilha vem de um token JWT VALIDADO, não de um cabeçalho livre.
 *
 * Aqui estão os dois lados da prova: (1) o que a aplicação RECUSA (sem token, assinatura de outra
 * chave, expirado, outro emissor, alg=none, claims adulteradas) e (2) o que ela ACEITA e como
 * grava (operador = upn; sem upn, o sub; o cabeçalho X-User-ID é ignorado).
 *
 * Requisição recusada não chega ao método anotado e, por isso, não gera evidência de ACESSO A
 * DADO. Esse limite é deliberado nesta versão e está documentado no README: a tentativa fica só
 * no span HTTP do rastro (status 401), não em audit_log.
 */
@QuarkusTest
class AutenticacaoTest {

    /** Envia a consulta de cidadão com o Authorization dado (ou sem ele, se nulo) e um trace próprio. */
    private String consultar(String autorizacao, String traceId, int statusEsperado) {
        var requisicao = given()
                .header("traceparent", "00-" + traceId + "-" + spanIdAleatorio() + "-01")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE));
        if (autorizacao != null) {
            requisicao = requisicao.header(AUTORIZACAO, autorizacao);
        }
        return requisicao
                .when().post(ROTA_CIDADAOS)
                .then().statusCode(statusEsperado)
                .extract().asString();
    }

    private void assertSemEvidencia(String traceId, String operador) {
        assertTrue(AuditLog.findByTraceId(traceId).isEmpty(),
                "requisição recusada não deve gerar evidência de acesso a dado");
        assertTrue(AuditLog.findByOperator(operador).isEmpty());
    }

    // ------------------------------------------------------------------
    // O que é recusado (401)
    // ------------------------------------------------------------------

    @Test
    void deveRecusarRequisicaoSemTokenENaoEntregarODado() {
        String traceId = traceIdAleatorio();

        String corpo = consultar(null, traceId, 401);

        assertFalse(corpo.contains("Joana"), "o dado do cidadão não pode ser entregue sem autenticação");
        assertFalse(corpo.contains(CPF_EXISTENTE));
        assertTrue(AuditLog.findByTraceId(traceId).isEmpty());
    }

    @Test
    void deveRecusarSemTokenTambemNoEndpointDeBeneficios() {
        given().contentType(ContentType.JSON).body(corpoCpf(CPF_EXISTENTE))
        .when().post(ROTA_BENEFICIOS)
        .then().statusCode(401);
    }

    @Test
    void deveRecusarTokenAssinadoPorOutraChave() {
        String operador = operador("atacante");
        String traceId = traceIdAleatorio();
        KeyPair outraChave = JwtMinter.gerarParDeChaves();
        String token = JwtMinter.novo().operador(operador).assinarCom(outraChave.getPrivate()).emitir();

        consultar("Bearer " + token, traceId, 401);

        assertSemEvidencia(traceId, operador);
    }

    @Test
    void deveRecusarTokenExpirado() {
        String operador = operador("expirado");
        String traceId = traceIdAleatorio();
        String token = JwtMinter.novo().operador(operador).expiraEm(Duration.ofHours(-1)).emitir();

        consultar("Bearer " + token, traceId, 401);

        assertSemEvidencia(traceId, operador);
    }

    @Test
    void deveRecusarTokenDeOutroEmissor() {
        String operador = operador("emissor");
        String traceId = traceIdAleatorio();
        String token = JwtMinter.novo().operador(operador).emissor("https://emissor-falso.example").emitir();

        consultar("Bearer " + token, traceId, 401);

        assertSemEvidencia(traceId, operador);
    }

    @Test
    void deveRecusarTokenSemAssinaturaComAlgNone() {
        String operador = operador("none");
        String traceId = traceIdAleatorio();
        String token = JwtMinter.novo().operador(operador).semAssinatura().emitir();

        consultar("Bearer " + token, traceId, 401);

        assertSemEvidencia(traceId, operador);
    }

    @Test
    void deveRecusarTokenComClaimAdulteradaDepoisDeAssinado() {
        String vitima = operador("vitima");
        String forjado = operador("forjado");
        String traceId = traceIdAleatorio();
        String valido = JwtMinter.novo().operador(vitima).emitir();

        String[] partes = valido.split("\\.", -1);
        String corpo = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                .replace("\"upn\":\"" + vitima + "\"", "\"upn\":\"" + forjado + "\"");
        assertTrue(corpo.contains(forjado), "o teste deve mesmo ter trocado o upn");
        String adulterado = partes[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(corpo.getBytes(StandardCharsets.UTF_8))
                + "." + partes[2];

        consultar("Bearer " + adulterado, traceId, 401);

        assertSemEvidencia(traceId, forjado);
    }

    @Test
    void deveRecusarCredencialQueNaoEBearer() {
        String operador = operador("basic");
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((operador + ":senha").getBytes(StandardCharsets.UTF_8));

        consultar(basic, traceIdAleatorio(), 401);

        assertTrue(AuditLog.findByOperator(operador).isEmpty());
    }

    // ------------------------------------------------------------------
    // O que é aceito e como é gravado
    // ------------------------------------------------------------------

    @Test
    void deveGravarComoOperadorOUpnDoTokenValidado() {
        String operador = operador("fiscal");
        String traceId = traceIdAleatorio();

        consultar(bearer(operador), traceId, 200);

        List<AuditLog> registros = AuditLog.findByTraceId(traceId);
        assertEquals(1, registros.size());
        assertEquals(operador, registros.get(0).operatorId);
    }

    @Test
    void deveIgnorarOCabecalhoXUserIdQuandoHaTokenValido() {
        String doToken = operador("do_token");
        String forjado = operador("forjado");

        given()
                .header(AUTORIZACAO, bearer(doToken))
                .header("X-User-ID", forjado)
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200);

        assertEquals(1, AuditLog.findByOperator(doToken).size(), "vale o operador do token");
        assertTrue(AuditLog.findByOperator(forjado).isEmpty(), "um cabeçalho livre não identifica ninguém");
    }

    @Test
    void deveUsarOSubQuandoOTokenNaoTemUpn() {
        String operador = operador("so_sub");
        String token = JwtMinter.novo().somenteSub(operador).emitir();

        consultar("Bearer " + token, traceIdAleatorio(), 200);

        assertEquals(1, AuditLog.findByOperator(operador).size(),
                "sem upn nem preferred_username, o principal é o sub (MicroProfile JWT)");
    }

    // ------------------------------------------------------------------
    // Papéis: a verificação da cadeia é só do auditor
    // ------------------------------------------------------------------

    @Test
    void deveExigirTokenEPapelAuditorParaVerificarACadeia() {
        given().when().get(ROTA_VERIFICACAO_CADEIA).then().statusCode(401);

        given().header(AUTORIZACAO, bearer(operador("operador")))
        .when().get(ROTA_VERIFICACAO_CADEIA)
        .then().statusCode(403);

        given().header(AUTORIZACAO, bearerAuditor(operador("auditor")))
        .when().get(ROTA_VERIFICACAO_CADEIA)
        .then().statusCode(200).body("valida", equalTo(true));
    }
}
