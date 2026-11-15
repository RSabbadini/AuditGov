package br.gov.auditgov.facade;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Serviço de cidadãos FALSO para os testes da fachada: um servidor HTTP do próprio JDK
 * (com.sun.net.httpserver), sem Quarkus, que GRAVA o que recebeu para o teste conferir.
 *
 * Por que um servidor falso e não o AuditGov de verdade: o que se quer provar aqui é o que a
 * fachada ENVIA (traceparent, Authorization, corpo) e como ela trata a resposta. O serviço
 * verdadeiro (JWT, banco, cadeia de hash) já tem os seus próprios testes; a prova conjunta, com
 * os dois serviços de verdade e o Jaeger, é o roteiro scripts/e2e-trace.sh.
 *
 * Como o servidor roda fora do Quarkus, não há estado estático compartilhado entre
 * classloaders: o teste e o servidor só conversam por HTTP.
 *
 * Comportamento:
 *   - sem cabeçalho Authorization: 401, sem corpo;
 *   - CPF 00000000404 no corpo: 404 COM um corpo de erro "interno" (para provar que a fachada
 *     não o repassa);
 *   - CPF 00000000503 no corpo: 503;
 *   - qualquer outro: 200 com um cidadão fictício em JSON.
 */
final class CidadaosFalso implements AutoCloseable {

    /** Porta fixa; é a mesma de quarkus.rest-client.citizen.url em src/test/resources. */
    static final int PORTA = 18089;
    static final String CPF_NAO_ENCONTRADO = "00000000404";
    static final String CPF_QUE_FALHA = "00000000503";
    static final String NOME = "Joana da Silva Pereira";
    static final String DETALHE_INTERNO = "detalhe interno do servico de cidadaos";

    private static final String CIDADAO_JSON = "{\"cpf\":\"12345678900\",\"nome\":\"" + NOME + "\","
            + "\"dataNascimento\":\"1988-04-12\","
            + "\"endereco\":\"Rua das Acácias, 120 - Centro - Campinas/SP - 13010-000\"}";

    /** Uma requisição como o serviço falso a recebeu (nomes de cabeçalho em minúsculas). */
    record Recebida(String metodo, String caminho, Map<String, String> cabecalhos, String corpo) {
        String cabecalho(String nome) {
            return cabecalhos.get(nome.toLowerCase(Locale.ROOT));
        }
    }

    private final HttpServer servidor;
    private final List<Recebida> recebidas = new CopyOnWriteArrayList<>();

    CidadaosFalso() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORTA), 0);
        servidor.createContext("/api/v1/cidadaos/consulta", this::tratar);
        servidor.start();
    }

    private void tratar(HttpExchange troca) throws IOException {
        try (troca) {
            String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            Map<String, String> cabecalhos = new HashMap<>();
            for (Map.Entry<String, List<String>> e : troca.getRequestHeaders().entrySet()) {
                if (!e.getValue().isEmpty()) {
                    cabecalhos.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
                }
            }
            recebidas.add(new Recebida(troca.getRequestMethod(), troca.getRequestURI().getPath(),
                    Map.copyOf(cabecalhos), corpo));

            if (!cabecalhos.containsKey("authorization")) {
                responder(troca, 401, null);
            } else if (corpo.contains(CPF_NAO_ENCONTRADO)) {
                responder(troca, 404, "{\"erro\":\"" + DETALHE_INTERNO + "\"}");
            } else if (corpo.contains(CPF_QUE_FALHA)) {
                responder(troca, 503, null);
            } else {
                responder(troca, 200, CIDADAO_JSON);
            }
        }
    }

    private static void responder(HttpExchange troca, int status, String json) throws IOException {
        if (json == null) {
            troca.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", "application/json");
        troca.sendResponseHeaders(status, bytes.length);
        try (OutputStream saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    void limpar() {
        recebidas.clear();
    }

    List<Recebida> recebidas() {
        return List.copyOf(recebidas);
    }

    /** A única requisição recebida desde o último {@link #limpar()}; falha se houver zero ou várias. */
    Recebida unica() {
        List<Recebida> lista = recebidas();
        if (lista.size() != 1) {
            throw new AssertionError("esperava 1 requisição ao serviço de cidadãos, recebeu " + lista.size());
        }
        return lista.get(0);
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
