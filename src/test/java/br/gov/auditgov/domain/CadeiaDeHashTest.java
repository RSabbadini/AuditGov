package br.gov.auditgov.domain;

import br.gov.auditgov.service.AuditCrypto;
import br.gov.auditgov.service.ChainVerifierService;
import br.gov.auditgov.util.AuditChain;
import br.gov.auditgov.util.AuditChain.Failure;
import br.gov.auditgov.util.AuditChain.Result;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static br.gov.auditgov.TestSupport.AUTORIZACAO;
import static br.gov.auditgov.TestSupport.CPF_EXISTENTE;
import static br.gov.auditgov.TestSupport.ROTA_CIDADAOS;
import static br.gov.auditgov.TestSupport.ROTA_VERIFICACAO_CADEIA;
import static br.gov.auditgov.TestSupport.bearer;
import static br.gov.auditgov.TestSupport.bearerAuditor;
import static br.gov.auditgov.TestSupport.corpoCpf;
import static br.gov.auditgov.TestSupport.operador;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cadeia de hashes com o Quarkus e o PostgreSQL reais: o que a aplicação grava encadeia, a
 * verificação confere, e uma alteração feita por quem desliga o gatilho é DETECTADA.
 *
 * Como a tabela é append-only e os outros testes compartilham o mesmo banco, as adulterações
 * daqui não podem deixar rastro: ou são desfeitas valor a valor (e o gatilho religado num
 * finally), ou acontecem dentro de uma transação que nunca é confirmada (rollback). Nos dois
 * casos a cadeia volta a ser válida ao fim de cada teste.
 *
 * O que estes testes NÃO provam: que um atacante que conhece a chave HMAC e escreve no banco seja
 * detectado sem âncora externa. Esse limite está em AuditChainTest (teste de lógica pura).
 */
@QuarkusTest
class CadeiaDeHashTest {

    @Inject
    DataSource dataSource;

    @Inject
    ChainVerifierService verificador;

    @Inject
    AuditCrypto crypto;

    private void consultar(String operador) {
        given()
                .header(AUTORIZACAO, bearer(operador))
                .contentType(ContentType.JSON)
                .body(corpoCpf(CPF_EXISTENTE))
        .when()
                .post(ROTA_CIDADAOS)
        .then()
                .statusCode(200);
    }

    private static void executar(Connection conexao, String sql) throws SQLException {
        try (Statement comando = conexao.createStatement()) {
            comando.execute(sql);
        }
    }

    private static long escalar(Connection conexao, String sql) throws SQLException {
        try (Statement comando = conexao.createStatement(); ResultSet rs = comando.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Lê a cadeia pela conexão dada (inclusive alterações não confirmadas) e a verifica. */
    private Result verificarPor(Connection conexao, Long ancoraSeq, String ancoraHash) throws SQLException {
        AuditChain.Verifier verifier = crypto.newChainVerifier(ancoraSeq, ancoraHash);
        String consulta = "select seq, id, trace_id, span_id, operator_id, action, resource, subject_ref, "
                + "payload_hash, event_timestamp, prev_hash, record_hash from audit_log order by seq";
        try (Statement comando = conexao.createStatement(); ResultSet rs = comando.executeQuery(consulta)) {
            while (rs.next()) {
                AuditChain.Row linha = new AuditChain.Row(
                        rs.getLong("seq"),
                        rs.getObject("id", UUID.class),
                        rs.getString("trace_id"),
                        rs.getString("span_id"),
                        rs.getString("operator_id"),
                        rs.getString("action"),
                        rs.getString("resource"),
                        rs.getString("subject_ref"),
                        rs.getString("payload_hash"),
                        rs.getObject("event_timestamp", OffsetDateTime.class).toInstant(),
                        rs.getString("prev_hash"),
                        rs.getString("record_hash"));
                if (!verifier.accept(linha)) {
                    break;
                }
            }
        }
        return verifier.finish();
    }

    // ------------------------------------------------------------------
    // O que a aplicação grava
    // ------------------------------------------------------------------

    @Test
    void deveManterACadeiaValidaEContinuaAposVariasConsultas() {
        for (int i = 0; i < 5; i++) {
            consultar(operador("cadeia"));
        }

        Result resultado = verificador.verify(null, null);

        assertTrue(resultado.valid(), resultado.toString());
        assertEquals(AuditLog.count(), resultado.checked(), "todos os registros foram conferidos");
        assertEquals(resultado.checked(), resultado.headSeq(), "seq contínuo, de 1 até o último");
    }

    @Test
    void cadaRegistroDeveGuardarOHashDoAnteriorEPoderSerRecalculadoAPartirDoBanco() {
        String operador = operador("encadeia");
        consultar(operador);
        consultar(operador);
        consultar(operador);

        List<AuditLog> ultimos = AuditLog.find("from AuditLog order by seq desc").page(0, 3).list();

        assertEquals(3, ultimos.size());
        assertEquals(ultimos.get(1).recordHash, ultimos.get(0).prevHash);
        assertEquals(ultimos.get(2).recordHash, ultimos.get(1).prevHash);
        assertEquals(ultimos.get(1).seq + 1, ultimos.get(0).seq);
        assertEquals(crypto.chainHash(ultimos.get(0).toChainRow()), ultimos.get(0).recordHash,
                "o hash gravado deve ser reproduzível a partir da linha lida do banco, "
                        + "incluindo o carimbo de tempo em microssegundos");
    }

    @Test
    void oPrimeiroRegistroDeveApontarParaOHashGenese() {
        consultar(operador("genese")); // garante que a tabela não está vazia

        AuditLog primeiro = AuditLog.find("from AuditLog where seq = 1").firstResult();

        assertNotNull(primeiro, "a cadeia começa na posição 1");
        assertEquals(AuditChain.GENESIS_HASH, primeiro.prevHash);
    }

    @Test
    void bancoDeveRecusarDoisRegistrosNaMesmaPosicaoDaCadeia() throws Exception {
        consultar(operador("duplicado"));

        try (Connection conexao = dataSource.getConnection()) {
            SQLException erro = assertThrows(SQLException.class, () -> executar(conexao,
                    "INSERT INTO audit_log (id, seq, trace_id, span_id, operator_id, action, resource, "
                            + "payload_hash, event_timestamp, prev_hash, record_hash) "
                            + "SELECT gen_random_uuid(), seq, trace_id, span_id, operator_id, action, resource, "
                            + "payload_hash, event_timestamp, prev_hash, record_hash "
                            + "FROM audit_log ORDER BY seq DESC LIMIT 1"));

            assertEquals("23505", erro.getSQLState(), "unique_violation: a cadeia não pode bifurcar");
        }
    }

    // ------------------------------------------------------------------
    // Adulteração por quem desliga o gatilho: detectada
    // ------------------------------------------------------------------

    @Test
    void deveDetectarConteudoAlteradoPorQuemDesativaOGatilho() throws Exception {
        String operador = operador("adulterado");
        consultar(operador);
        long seqAlvo = AuditLog.findByOperator(operador).get(0).seq;

        try (Connection conexao = dataSource.getConnection()) {
            try {
                executar(conexao, "ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update_delete");
                executar(conexao, "UPDATE audit_log SET operator_id = 'adulterado' WHERE seq = " + seqAlvo);

                Result adulterada = verificador.verify(null, null);

                assertFalse(adulterada.valid());
                assertEquals(seqAlvo, adulterada.failedSeq());
                assertEquals(Failure.RECORD_HASH_MISMATCH, adulterada.reason());
            } finally {
                executar(conexao, "UPDATE audit_log SET operator_id = '" + operador + "' WHERE seq = " + seqAlvo);
                executar(conexao, "ALTER TABLE audit_log ENABLE TRIGGER audit_log_no_update_delete");
            }
        }

        assertTrue(verificador.verify(null, null).valid(),
                "com o valor original restaurado, a cadeia volta a conferir");
    }

    @Test
    void deveDetectarRegistroRemovidoNoMeioDaCadeia() throws Exception {
        for (int i = 0; i < 3; i++) {
            consultar(operador("meio"));
        }

        try (Connection conexao = dataSource.getConnection()) {
            conexao.setAutoCommit(false); // nada disto será confirmado
            try {
                executar(conexao, "ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update_delete");
                long ultimo = escalar(conexao, "select max(seq) from audit_log");
                executar(conexao, "DELETE FROM audit_log WHERE seq = " + (ultimo - 1));

                Result resultado = verificarPor(conexao, null, null);

                assertFalse(resultado.valid());
                assertEquals(Failure.SEQ_GAP, resultado.reason());
                assertEquals(ultimo, resultado.failedSeq(), "o defeito aparece no registro seguinte ao buraco");
            } finally {
                conexao.rollback();
            }
        }

        assertTrue(verificador.verify(null, null).valid(), "o rollback devolveu o banco ao que era");
    }

    @Test
    void remocaoDosUltimosRegistrosSoApareceComAncoraGuardadaForaDoBanco() throws Exception {
        consultar(operador("cauda"));
        consultar(operador("cauda"));
        Result antes = verificador.verify(null, null);
        assertTrue(antes.valid());
        long ancoraSeq = antes.headSeq();
        String ancoraHash = antes.headHash();

        try (Connection conexao = dataSource.getConnection()) {
            conexao.setAutoCommit(false); // nada disto será confirmado
            try {
                executar(conexao, "ALTER TABLE audit_log DISABLE TRIGGER audit_log_no_update_delete");
                executar(conexao, "DELETE FROM audit_log WHERE seq > " + (ancoraSeq - 2));

                Result semAncora = verificarPor(conexao, null, null);
                Result comAncora = verificarPor(conexao, ancoraSeq, ancoraHash);

                assertTrue(semAncora.valid(),
                        "LIMITE: sem âncora, uma cadeia mais curta e íntegra é aprovada");
                assertFalse(comAncora.valid());
                assertEquals(Failure.TAIL_TRUNCATED, comAncora.reason());
            } finally {
                conexao.rollback();
            }
        }

        assertTrue(verificador.verify(ancoraSeq, ancoraHash).valid(), "o rollback devolveu o banco ao que era");
    }

    // ------------------------------------------------------------------
    // Endpoint de verificação
    // ------------------------------------------------------------------

    private io.restassured.specification.RequestSpecification comoAuditor() {
        return given().header(AUTORIZACAO, bearerAuditor(operador("auditor")));
    }

    @Test
    void endpointDeveAceitarAncoraEAcusarAncoraDeOutraCadeia() {
        consultar(operador("ancora"));
        ExtractableResponse<Response> base = comoAuditor()
                .when().get(ROTA_VERIFICACAO_CADEIA)
                .then().statusCode(200).body("valida", equalTo(true))
                .extract();
        Number seq = base.path("ultimoSeq");
        String hash = base.path("ultimoHash");
        assertNotNull(seq);
        assertNotNull(hash);

        consultar(operador("ancora")); // a cadeia cresce depois de a âncora ser guardada

        comoAuditor().queryParam("ancoraSeq", seq).queryParam("ancoraHash", hash)
                .when().get(ROTA_VERIFICACAO_CADEIA)
                .then().statusCode(200).body("valida", equalTo(true));

        comoAuditor().queryParam("ancoraSeq", seq).queryParam("ancoraHash", "0".repeat(64))
                .when().get(ROTA_VERIFICACAO_CADEIA)
                .then().statusCode(200)
                .body("valida", equalTo(false))
                .body("motivo", equalTo("ANCHOR_MISMATCH"));
    }

    @Test
    void endpointDeveRecusarAncoraIncompletaOuForaDoFormato() {
        comoAuditor().queryParam("ancoraSeq", 1)
                .when().get(ROTA_VERIFICACAO_CADEIA)
                .then().statusCode(400);

        comoAuditor().queryParam("ancoraHash", "0".repeat(64))
                .when().get(ROTA_VERIFICACAO_CADEIA)
                .then().statusCode(400);

        comoAuditor().queryParam("ancoraSeq", 1).queryParam("ancoraHash", "xyz")
                .when().get(ROTA_VERIFICACAO_CADEIA)
                .then().statusCode(400);
    }
}
