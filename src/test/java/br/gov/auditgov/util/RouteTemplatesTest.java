package br.gov.auditgov.util;

import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Garante que o campo resource recebe o MODELO de rota e nunca o caminho real:
 * um método com {@code /{cpf}} continua aparecendo como {@code /{cpf}}.
 */
class RouteTemplatesTest {

    @Path("/api/v1/exemplo")
    public static class Recurso {

        @Path("/consulta")
        public void comCaminhoFixo() {
        }

        @Path("/{cpf}")
        public void comParametroDeCaminho() {
        }

        public void semCaminhoProprio() {
        }
    }

    public static class NaoEhRecurso {

        public void qualquer() {
        }
    }

    @Test
    void deveCombinarCaminhoDaClasseEDoMetodo() throws Exception {
        assertEquals("/api/v1/exemplo/consulta",
                RouteTemplates.templateOf(Recurso.class.getMethod("comCaminhoFixo")));
    }

    @Test
    void deveManterOModeloEmVezDoValorDoParametro() throws Exception {
        assertEquals("/api/v1/exemplo/{cpf}",
                RouteTemplates.templateOf(Recurso.class.getMethod("comParametroDeCaminho")));
    }

    @Test
    void deveUsarSoCaminhoDaClasseQuandoOMetodoNaoTemPath() throws Exception {
        assertEquals("/api/v1/exemplo",
                RouteTemplates.templateOf(Recurso.class.getMethod("semCaminhoProprio")));
    }

    @Test
    void deveDevolverNuloParaClasseQueNaoEhRecursoJaxRs() throws Exception {
        assertNull(RouteTemplates.templateOf(NaoEhRecurso.class.getMethod("qualquer")));
    }

    @Test
    void deveNormalizarBarras() {
        assertEquals("/a/b", RouteTemplates.join("a/", "/b/"));
        assertEquals("/", RouteTemplates.join("", ""));
        assertEquals("/api", RouteTemplates.join("api", ""));
    }
}
