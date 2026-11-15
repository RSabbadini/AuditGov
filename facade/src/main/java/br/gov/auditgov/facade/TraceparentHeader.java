package br.gov.auditgov.facade;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

/**
 * Monta o valor do cabeçalho W3C Trace Context {@code traceparent} (versão 00):
 * {@code 00-<trace-id: 32 hex>-<span-id: 16 hex>-<flags: 2 hex>}.
 *
 * Esta classe só é usada no modo de propagação MANUAL ({@code facade.trace.manual-propagation=true}),
 * uma contingência para o caso de a instrumentação automática do Quarkus não injetar o cabeçalho
 * na chamada ao serviço de cidadãos. No modo padrão (automático) ela não é chamada.
 *
 * O traceparent serve para CORRELAÇÃO: liga os spans de dois serviços no mesmo trace. Ele não
 * autentica nem autoriza ninguém e não carrega dado pessoal.
 */
public final class TraceparentHeader {

    /** Nome do cabeçalho (W3C Trace Context). */
    public static final String NOME = "traceparent";

    private static final String VERSAO = "00";

    private TraceparentHeader() {
        // Classe utilitária.
    }

    /**
     * Formata o valor do cabeçalho.
     *
     * @throws IllegalArgumentException se o trace-id não tiver 32 hexadecimais minúsculos, se o
     *                                  span-id não tiver 16, ou se algum deles for só zeros
     *                                  (inválidos pela especificação W3C).
     */
    public static String formatar(String traceId, String spanId, boolean amostrado) {
        if (!hexMinusculo(traceId, 32) || todoZero(traceId)) {
            throw new IllegalArgumentException(
                    "trace-id deve ter 32 hexadecimais minúsculos e não pode ser só zeros");
        }
        if (!hexMinusculo(spanId, 16) || todoZero(spanId)) {
            throw new IllegalArgumentException(
                    "span-id deve ter 16 hexadecimais minúsculos e não pode ser só zeros");
        }
        return VERSAO + "-" + traceId + "-" + spanId + "-" + (amostrado ? "01" : "00");
    }

    /**
     * Valor do traceparent para o span ATUAL (o span do servidor HTTP da fachada), ou
     * {@code null} se não houver contexto de rastreamento válido (por exemplo, OpenTelemetry
     * desligado).
     */
    public static String doSpanAtual() {
        SpanContext contexto = Span.current().getSpanContext();
        if (!contexto.isValid()) {
            return null;
        }
        return formatar(contexto.getTraceId(), contexto.getSpanId(), contexto.isSampled());
    }

    private static boolean hexMinusculo(String valor, int tamanho) {
        if (valor == null || valor.length() != tamanho) {
            return false;
        }
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean todoZero(String valor) {
        for (int i = 0; i < valor.length(); i++) {
            if (valor.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
