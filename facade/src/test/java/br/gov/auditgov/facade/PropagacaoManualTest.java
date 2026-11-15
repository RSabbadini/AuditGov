package br.gov.auditgov.facade;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static br.gov.auditgov.facade.ApoioTeste.CPF_EXISTENTE;
import static br.gov.auditgov.facade.ApoioTeste.ROTA;
import static br.gov.auditgov.facade.ApoioTeste.corpoCpf;
import static br.gov.auditgov.facade.ApoioTeste.spanIdAleatorio;
import static br.gov.auditgov.facade.ApoioTeste.traceIdAleatorio;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contingência: com facade.trace.manual-propagation=true a fachada monta o traceparent a partir
 * do span atual, sem depender da injeção automática do cliente REST.
 *
 * Se PropagacaoTest falhar e este passar, a instrumentação automática não injetou o cabeçalho
 * nesta versão do Quarkus: deixe facade.trace.manual-propagation=true em application.properties.
 * Se os dois passarem, a propagação automática funciona e o modo manual é desnecessário.
 */
@QuarkusTest
@TestProfile(PropagacaoManualTest.PerfilManual.class)
class PropagacaoManualTest {

    public static class PerfilManual implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("facade.trace.manual-propagation", "true");
        }
    }

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

    @Test
    void deveMontarOTraceparentDoSpanAtualQuandoAPropagacaoManualEstaLigada() {
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
        assertEquals(traceId, recebido.substring(3, 35), "mesmo trace");
        assertNotEquals(spanIdDoCliente, recebido.substring(36, 52),
                "o pai do span chamado deve ser um span da FACHADA");
    }
}
