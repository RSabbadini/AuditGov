package br.gov.auditgov.interceptor;

import br.gov.auditgov.domain.AuditLog;
import br.gov.auditgov.service.ChainVerifierService;
import br.gov.auditgov.util.AuditChain;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_CIDADAOS;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.spanIdAleatorio;
import static br.gov.auditgov.TestSupport.traceIdAleatorio;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cem requisições simultâneas, cada uma com um token (operador) e um traceparent próprios.
 * Verifica que nenhuma evidência mistura o contexto de outra requisição: cada
 * operador tem exatamente uma linha e o trace_id gravado é o que ele enviou.
 *
 * Verifica também a cadeia de hashes sob concorrência: as cem gravações simultâneas ocupam
 * cem posições consecutivas, sem repetição nem buraco, e a cadeia inteira continua íntegra.
 * É isso que o bloqueio de serialização de AuditLogService garante.
 *
 * É um teste de integridade, não de desempenho.
 */
@QuarkusTest
class ConcurrencyTest {

    private static final int REQUISICOES = 100;

    @Inject
    ChainVerifierService verificador;

    @Test
    void deveManterOperadorETraceDeCadaRequisicaoComCemChamadasParalelas() throws Exception {
        String prefixo = "par_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String[]>> envios = new ArrayList<>();

        try {
            for (int i = 0; i < REQUISICOES; i++) {
                final String operador = prefixo + i;
                final String traceId = traceIdAleatorio();
                envios.add(pool.submit(() -> {
                    largada.await();
                    given()
                            .header(AUTORIZACAO, bearer(operador))
                            .header("traceparent", "00-" + traceId + "-" + spanIdAleatorio() + "-01")
                            .contentType(ContentType.JSON)
                            .body(corpoCpf(CPF_EXISTENTE))
                    .when()
                            .post(ROTA_CIDADAOS)
                    .then()
                            .statusCode(200);
                    return new String[] {operador, traceId};
                }));
            }
            largada.countDown();

            Set<String> tracesVistos = new HashSet<>();
            long menorSeq = Long.MAX_VALUE;
            long maiorSeq = Long.MIN_VALUE;
            Set<Long> posicoes = new HashSet<>();
            for (Future<String[]> envio : envios) {
                String[] enviado = envio.get(60, TimeUnit.SECONDS);
                String operador = enviado[0];
                String traceEnviado = enviado[1];

                List<AuditLog> registros = AuditLog.findByOperator(operador);
                assertEquals(1, registros.size(), "o operador " + operador + " deve ter exatamente uma evidência");
                assertEquals(traceEnviado, registros.get(0).traceId,
                        "o trace_id gravado deve ser o da própria requisição");
                tracesVistos.add(registros.get(0).traceId);
                long seq = registros.get(0).seq;
                posicoes.add(seq);
                menorSeq = Math.min(menorSeq, seq);
                maiorSeq = Math.max(maiorSeq, seq);
            }
            assertEquals(REQUISICOES, tracesVistos.size(), "nenhum trace_id pode se repetir entre requisições");

            assertEquals(REQUISICOES, posicoes.size(), "cada gravação ocupa uma posição própria na cadeia");
            assertEquals(REQUISICOES - 1L, maiorSeq - menorSeq,
                    "as posições são consecutivas: nenhum buraco entre as cem gravações simultâneas");
            AuditChain.Result cadeia = verificador.verify(null, null);
            assertTrue(cadeia.valid(), "a cadeia deve continuar íntegra depois da carga: " + cadeia);
        } finally {
            pool.shutdownNow();
        }
    }
}
