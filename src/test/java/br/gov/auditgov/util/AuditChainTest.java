package br.gov.auditgov.util;

import br.gov.auditgov.util.AuditChain.Failure;
import br.gov.auditgov.util.AuditChain.Result;
import br.gov.auditgov.util.AuditChain.Row;
import br.gov.auditgov.util.AuditChain.Verifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lógica da cadeia de hash, sem Quarkus nem banco: roda em milissegundos.
 *
 * Os dois vetores de referência (hash1 e hash2) foram calculados POR FORA do Java, com
 * a biblioteca padrão do Python (hmac + hashlib), seguindo só a descrição do formato
 * canônico em {@link AuditChain}. Se a implementação Java e a descrição divergirem, os
 * vetores acusam.
 */
class AuditChainTest {

    private static final byte[] CHAVE =
            "chave-da-cadeia-para-testes-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final Instant T0 = Instant.parse("2026-11-12T10:00:00.123456Z");

    private static final String HASH_1 = "d069b04b8a0cc65ed3167cd65fc775dcf03d43f7007501c9f8af4012beec85b8";
    private static final String HASH_2 = "4b045f0dfeefb13794ed683cac61ca92743088ef3d0cf0d1bf2fe47bdb627573";

    // ------------------------------------------------------------------
    // Cálculo do hash
    // ------------------------------------------------------------------

    private static Row primeiroDeReferencia() {
        return new Row(1, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", "fiscal_maria",
                "READ_CITIZEN_DATA", "POST /api/v1/cidadaos/consulta",
                "ab".repeat(32), "cd".repeat(32), T0, AuditChain.GENESIS_HASH, null);
    }

    private static Row segundoDeReferencia() {
        return new Row(2, UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "11111111111111111111111111111111", "2222222222222222", "auditor_joao",
                "READ_CITIZEN_DATA_FAILED", "POST /api/v1/cidadaos/consulta",
                null, "ef".repeat(32), T0.plusSeconds(1), HASH_1, null);
    }

    @Test
    void deveReproduzirOVetorDeReferenciaDoPrimeiroRegistro() {
        assertEquals(HASH_1, AuditChain.hash(CHAVE, primeiroDeReferencia()));
    }

    @Test
    void deveReproduzirOVetorDeReferenciaDoSegundoRegistroEncadeadoAoPrimeiro() {
        assertEquals(HASH_2, AuditChain.hash(CHAVE, segundoDeReferencia()),
                "subject_ref nulo entra como campo vazio e o prev_hash é o hash do primeiro");
    }

    @Test
    void deveDescreverOFormatoCanonicoCampoACampo() {
        String esperado = String.join("\n",
                "auditgov-chain-v1",
                "1",
                "00000000-0000-0000-0000-000000000001",
                "0af7651916cd43dd8448eb211c80319c",
                "b7ad6b7169203331",
                "fiscal_maria",
                "READ_CITIZEN_DATA",
                "POST /api/v1/cidadaos/consulta",
                "ab".repeat(32),
                "cd".repeat(32),
                "1794477600123456",
                "0".repeat(64));

        assertEquals(esperado, new String(AuditChain.canonical(primeiroDeReferencia()), StandardCharsets.UTF_8));
    }

    @Test
    void deveMudarOHashQuandoQualquerCampoMuda() {
        Row base = primeiroDeReferencia();
        String hashBase = AuditChain.hash(CHAVE, base);
        UUID outroId = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

        List<Row> variacoes = List.of(
                new Row(2, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, outroId, base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), "0".repeat(32), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), "0".repeat(16), base.operatorId(), base.action(),
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), "fiscal_mario", base.action(),
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), "READ_BENEFIT_DATA",
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        "GET /outra", base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), "ba".repeat(32), base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), null, base.payloadHash(), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), base.subjectRef(), "dc".repeat(32), base.timestamp(), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp().plusNanos(1_000), base.prevHash(), null),
                new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                        base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), "1".repeat(64), null));

        for (int i = 0; i < variacoes.size(); i++) {
            assertNotEquals(hashBase, AuditChain.hash(CHAVE, variacoes.get(i)),
                    "alterar o campo da variação " + i + " deveria mudar o hash");
        }
    }

    @Test
    void naoDeveDependerDoRecordHashJaGravadoNemDeFracoesAbaixoDeMicrossegundo() {
        Row base = primeiroDeReferencia();
        Row comHashQualquer = new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), "ff".repeat(32));
        Row comNanos = new Row(1, base.id(), base.traceId(), base.spanId(), base.operatorId(), base.action(),
                base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp().plusNanos(789), base.prevHash(), null);

        assertEquals(AuditChain.hash(CHAVE, base), AuditChain.hash(CHAVE, comHashQualquer),
                "o próprio record_hash não entra no cálculo");
        assertEquals(AuditChain.hash(CHAVE, base), AuditChain.hash(CHAVE, comNanos),
                "o PostgreSQL guarda microssegundos: frações menores são truncadas, não arredondadas");
    }

    @Test
    void deveTruncarParaMicrossegundosSemArredondar() {
        assertEquals(1_999_999L, AuditChain.epochMicros(Instant.ofEpochSecond(1, 999_999_999)));
        assertEquals(1794477600123456L, AuditChain.epochMicros(T0));
        assertEquals(-1L, AuditChain.epochMicros(Instant.ofEpochSecond(0, -1_000)));
    }

    @Test
    void deveGerarHashDiferenteComChaveDiferente() {
        String comOutraChave = AuditChain.hash(
                "outra-chave-0123456789-0123456789".getBytes(StandardCharsets.UTF_8), primeiroDeReferencia());

        assertNotEquals(HASH_1, comOutraChave);
        assertEquals(64, comOutraChave.length());
    }

    @Test
    void deveRecusarCampoComQuebraDeLinhaEmVezDeGravarDeFormaAmbigua() {
        Row base = primeiroDeReferencia();
        Row comLf = new Row(1, base.id(), base.traceId(), base.spanId(), "fiscal\nmaria", base.action(),
                base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null);
        Row comCr = new Row(1, base.id(), base.traceId(), base.spanId(), "fiscal\rmaria", base.action(),
                base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null);

        assertThrows(IllegalArgumentException.class, () -> AuditChain.canonical(comLf));
        assertThrows(IllegalArgumentException.class, () -> AuditChain.canonical(comCr));
    }

    @Test
    void deveRecusarCampoObrigatorioAusente() {
        Row base = primeiroDeReferencia();
        Row semOperador = new Row(1, base.id(), base.traceId(), base.spanId(), null, base.action(),
                base.resource(), base.subjectRef(), base.payloadHash(), base.timestamp(), base.prevHash(), null);

        assertThrows(IllegalArgumentException.class, () -> AuditChain.canonical(semOperador));
    }

    // ------------------------------------------------------------------
    // Verificação
    // ------------------------------------------------------------------

    /** Cadeia íntegra de n registros, montada com a mesma função de hash da aplicação. */
    private static List<Row> cadeia(int n) {
        List<Row> linhas = new ArrayList<>();
        String anterior = AuditChain.GENESIS_HASH;
        for (int i = 1; i <= n; i++) {
            Row semHash = new Row(i,
                    UUID.nameUUIDFromBytes(("id" + i).getBytes(StandardCharsets.UTF_8)),
                    String.format("%032x", i), String.format("%016x", i), "operador_" + i,
                    "READ_CITIZEN_DATA", "POST /api/v1/cidadaos/consulta",
                    i % 2 == 0 ? null : String.format("%064x", i * 31L),
                    String.format("%064x", i * 17L), T0.plusSeconds(i), anterior, null);
            String hash = AuditChain.hash(CHAVE, semHash);
            linhas.add(comHash(semHash, hash));
            anterior = hash;
        }
        return linhas;
    }

    private static Row comHash(Row r, String hash) {
        return new Row(r.seq(), r.id(), r.traceId(), r.spanId(), r.operatorId(), r.action(), r.resource(),
                r.subjectRef(), r.payloadHash(), r.timestamp(), r.prevHash(), hash);
    }

    private static Row comOperador(Row r, String operador) {
        return new Row(r.seq(), r.id(), r.traceId(), r.spanId(), operador, r.action(), r.resource(),
                r.subjectRef(), r.payloadHash(), r.timestamp(), r.prevHash(), r.recordHash());
    }

    private static Row comPrev(Row r, String prev) {
        return new Row(r.seq(), r.id(), r.traceId(), r.spanId(), r.operatorId(), r.action(), r.resource(),
                r.subjectRef(), r.payloadHash(), r.timestamp(), prev, r.recordHash());
    }

    private static Result verificar(List<Row> linhas) {
        return verificar(linhas, null, null);
    }

    private static Result verificar(List<Row> linhas, Long ancoraSeq, String ancoraHash) {
        Verifier verificador = new Verifier(CHAVE, ancoraSeq, ancoraHash);
        for (Row linha : linhas) {
            if (!verificador.accept(linha)) {
                break;
            }
        }
        return verificador.finish();
    }

    @Test
    void deveAprovarCadeiaIntegra() {
        Result resultado = verificar(cadeia(5));

        assertTrue(resultado.valid());
        assertEquals(5, resultado.checked());
        assertEquals(5L, resultado.headSeq());
        assertEquals(cadeia(5).get(4).recordHash(), resultado.headHash());
        assertNull(resultado.failedSeq());
        assertNull(resultado.reason());
    }

    @Test
    void deveAprovarTabelaVaziaSemAncora() {
        Result resultado = verificar(List.of());

        assertTrue(resultado.valid());
        assertEquals(0, resultado.checked());
        assertNull(resultado.headSeq());
    }

    @Test
    void deveDetectarConteudoAlteradoSemRecalcularOHash() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        linhas.set(2, comOperador(linhas.get(2), "adulterado"));

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(3L, resultado.failedSeq());
        assertEquals(Failure.RECORD_HASH_MISMATCH, resultado.reason());
        assertEquals(2, resultado.checked(), "os dois primeiros registros foram aprovados");
        assertEquals(2L, resultado.headSeq());
    }

    @Test
    void deveDetectarHashGravadoAlterado() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        linhas.set(2, comHash(linhas.get(2), "0".repeat(64)));

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(3L, resultado.failedSeq());
        assertEquals(Failure.RECORD_HASH_MISMATCH, resultado.reason());
    }

    @Test
    void deveDetectarPrevHashAlterado() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        linhas.set(3, comPrev(linhas.get(3), "f".repeat(64)));

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(4L, resultado.failedSeq());
        assertEquals(Failure.PREV_HASH_MISMATCH, resultado.reason());
    }

    @Test
    void deveDetectarRegistroRemovidoNoMeio() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        linhas.remove(2); // some o seq 3

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(4L, resultado.failedSeq(), "o defeito aparece no primeiro registro depois do buraco");
        assertEquals(Failure.SEQ_GAP, resultado.reason());
    }

    @Test
    void deveDetectarRemocaoDosPrimeirosRegistros() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        linhas.remove(0);
        linhas.remove(0);

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(3L, resultado.failedSeq());
        assertEquals(Failure.SEQ_GAP, resultado.reason(), "a cadeia deve começar no seq 1");
    }

    @Test
    void deveDetectarRegistroDuplicadoOuInseridoForaDeLugar() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        linhas.add(3, linhas.get(2)); // 1, 2, 3, 3, 4, 5

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(Failure.SEQ_GAP, resultado.reason());
    }

    @Test
    void deveDetectarTrocaDeOrdem() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        Row segundo = linhas.get(1);
        linhas.set(1, linhas.get(2));
        linhas.set(2, segundo);

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(Failure.SEQ_GAP, resultado.reason());
    }

    @Test
    void quemNaoTemAChaveNaoConsegueEsconderAAlteracaoRecalculandoOHash() {
        List<Row> linhas = new ArrayList<>(cadeia(5));
        Row alterada = comOperador(linhas.get(2), "adulterado");
        byte[] chaveDoAtacante = "o-atacante-nao-conhece-a-chave".getBytes(StandardCharsets.UTF_8);
        linhas.set(2, comHash(alterada, AuditChain.hash(chaveDoAtacante, alterada)));

        Result resultado = verificar(linhas);

        assertFalse(resultado.valid());
        assertEquals(3L, resultado.failedSeq());
        assertEquals(Failure.RECORD_HASH_MISMATCH, resultado.reason());
    }

    @Test
    void umDigestSemChaveNaoSeguraACadeia() {
        // Se o hash fosse SHA-256 simples, o atacante recalcularia o registro alterado e
        // todos os seguintes. Aqui o verificador usa HMAC, então esse recálculo reprova.
        List<Row> linhas = new ArrayList<>(cadeia(5));
        Row alterada = comOperador(linhas.get(2), "adulterado");
        String sha256Simples = sha256Hex(AuditChain.canonical(alterada));
        linhas.set(2, comHash(alterada, sha256Simples));

        assertEquals(Failure.RECORD_HASH_MISMATCH, verificar(linhas).reason());
    }

    private static String sha256Hex(byte[] dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void quemTemAChaveERacessoDeEscritaReescreveACadeiaSemSerDetectadoPelaVerificacaoInterna() {
        // LIMITE documentado: com a chave e escrita no banco, o atacante refaz os registros
        // 3 a 5 de forma consistente. A verificação interna aprova. Só uma âncora externa acusa.
        List<Row> original = cadeia(5);
        List<Row> reescrita = new ArrayList<>(original.subList(0, 2));
        String anterior = reescrita.get(1).recordHash();
        for (int i = 2; i < 5; i++) {
            Row r = original.get(i);
            Row refeito = new Row(r.seq(), r.id(), r.traceId(), r.spanId(), "adulterado_" + i, r.action(),
                    r.resource(), r.subjectRef(), r.payloadHash(), r.timestamp(), anterior, null);
            String hash = AuditChain.hash(CHAVE, refeito);
            reescrita.add(comHash(refeito, hash));
            anterior = hash;
        }

        assertTrue(verificar(reescrita).valid(), "sem âncora, a reescrita com a chave passa");

        Result comAncora = verificar(reescrita, 5L, original.get(4).recordHash());
        assertFalse(comAncora.valid());
        assertEquals(5L, comAncora.failedSeq());
        assertEquals(Failure.ANCHOR_MISMATCH, comAncora.reason());
    }

    @Test
    void remocaoDosUltimosRegistrosSoApareceComAncora() {
        List<Row> original = cadeia(5);
        List<Row> truncada = new ArrayList<>(original.subList(0, 3));

        Result semAncora = verificar(truncada);
        assertTrue(semAncora.valid(), "LIMITE: sem âncora, uma cadeia mais curta e íntegra é aprovada");
        assertEquals(3L, semAncora.headSeq());

        Result comAncora = verificar(truncada, 5L, original.get(4).recordHash());
        assertFalse(comAncora.valid());
        assertEquals(Failure.TAIL_TRUNCATED, comAncora.reason());
        assertEquals(5L, comAncora.failedSeq());
    }

    @Test
    void deveDetectarTabelaEsvaziadaQuandoHaAncora() {
        Result resultado = verificar(List.of(), 1L, cadeia(1).get(0).recordHash());

        assertFalse(resultado.valid());
        assertEquals(Failure.TAIL_TRUNCATED, resultado.reason());
    }

    @Test
    void deveAceitarAncoraNoMeioDaCadeiaQuandoACadeiaCresceu() {
        List<Row> linhas = cadeia(5);

        Result resultado = verificar(linhas, 3L, linhas.get(2).recordHash());

        assertTrue(resultado.valid());
        assertEquals(5, resultado.checked());
    }

    @Test
    void deveRecusarAncoraIncompleta() {
        assertThrows(IllegalArgumentException.class, () -> new Verifier(CHAVE, 3L, null));
        assertThrows(IllegalArgumentException.class, () -> new Verifier(CHAVE, null, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new Verifier(CHAVE, 0L, "a".repeat(64)));
    }

    @Test
    void devePararNoPrimeiroDefeitoEManterOPrimeiroMotivo() {
        List<Row> linhas = new ArrayList<>(cadeia(6));
        linhas.set(1, comOperador(linhas.get(1), "adulterado"));
        linhas.set(4, comOperador(linhas.get(4), "adulterado"));

        Verifier verificador = new Verifier(CHAVE);
        assertTrue(verificador.accept(linhas.get(0)));
        assertFalse(verificador.accept(linhas.get(1)));
        assertTrue(verificador.failed());
        assertFalse(verificador.accept(linhas.get(2)), "depois do defeito nada mais é aceito");

        Result resultado = verificador.finish();
        assertEquals(2L, resultado.failedSeq(), "vale o primeiro defeito, não o último");
    }
}
