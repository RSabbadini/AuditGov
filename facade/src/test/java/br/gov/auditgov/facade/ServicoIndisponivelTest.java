package br.gov.auditgov.facade;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

import static br.gov.auditgov.facade.ApoioTeste.CPF_EXISTENTE;
import static br.gov.auditgov.facade.ApoioTeste.ROTA;
import static br.gov.auditgov.facade.ApoioTeste.corpoCpf;
import static io.restassured.RestAssured.given;

/**
 * Se o serviço de cidadãos não responde (conexão recusada), a fachada devolve 502 em vez de um
 * erro interno sem explicação. O endereço do serviço aponta para uma porta que estava livre
 * (e foi fechada) um instante antes de a aplicação subir.
 */
@QuarkusTest
@TestProfile(ServicoIndisponivelTest.PerfilPortaFechada.class)
class ServicoIndisponivelTest {

    public static class PerfilPortaFechada implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.rest-client.citizen.url", "http://127.0.0.1:" + portaLivre());
        }

        private static int portaLivre() {
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Test
    void deveResponder502QuandoOServicoDeCidadaosNaoResponde() {
        given()
                .header("Authorization", "Bearer token-de-teste")
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA)
        .then()
                .statusCode(502);
    }
}
