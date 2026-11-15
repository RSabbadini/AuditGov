package br.gov.auditgov.util;

import jakarta.ws.rs.Path;

import java.lang.reflect.Method;

/**
 * Descobre o MODELO de rota de um método JAX-RS (por exemplo
 * {@code /api/v1/cidadaos/{cpf}}) a partir das anotações {@code @Path}, e nunca
 * o caminho real da requisição.
 *
 * O caminho real pode conter identificadores de pessoas (um CPF na URL, por
 * exemplo). Gravar o modelo evita que esses valores cheguem à tabela de
 * auditoria e aos spans do Jaeger por meio do campo resource.
 */
public final class RouteTemplates {

    private RouteTemplates() {
        // Classe utilitária.
    }

    /**
     * Retorna o modelo de rota do método, ou {@code null} se nem o método nem a
     * classe tiverem {@code @Path} (ou seja, não é um recurso JAX-RS).
     */
    public static String templateOf(Method method) {
        if (method == null) {
            return null;
        }
        Path classPath = findClassPath(method.getDeclaringClass());
        Path methodPath = method.getAnnotation(Path.class);
        if (classPath == null && methodPath == null) {
            return null;
        }
        return join(classPath == null ? "" : classPath.value(),
                methodPath == null ? "" : methodPath.value());
    }

    /** @Path não é herdada pelo Java; sobe a hierarquia para achar a do recurso. */
    private static Path findClassPath(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            Path path = c.getAnnotation(Path.class);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    static String join(String base, String sub) {
        String b = trimSlashes(base);
        String s = trimSlashes(sub);
        StringBuilder out = new StringBuilder("/").append(b);
        if (!b.isEmpty() && !s.isEmpty()) {
            out.append('/');
        }
        return out.append(s).toString();
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
