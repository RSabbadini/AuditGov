package br.gov.auditgov.facade;

/**
 * Corpo {@code {"cpf": "..."}} recebido pela fachada e repassado, sem alteração, ao serviço de
 * cidadãos.
 *
 * O CPF viaja só no CORPO (nunca na URL) e a fachada não o grava nem o escreve em log. O
 * {@code toString} mascara o valor para que um log descuidado do objeto não vaze o
 * identificador do titular.
 */
public record ConsultaRequest(String cpf) {

    @Override
    public String toString() {
        return "ConsultaRequest[cpf=***]";
    }
}
