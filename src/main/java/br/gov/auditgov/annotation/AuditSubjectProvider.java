package br.gov.auditgov.annotation;

/**
 * Contrato opcional para identificar o TITULAR dos dados consultados.
 *
 * Se um dos parâmetros do método anotado com {@link Auditable} implementar esta
 * interface, o interceptador usa {@link #auditSubjectId()} para gravar uma
 * referência pseudonimizada do titular (HMAC-SHA-256 com chave fora do banco) no
 * campo subject_ref. Isso permite responder "quem acessou os dados deste
 * titular?" (LGPD, Art. 18) sem gravar o CPF na trilha.
 *
 * O recurso de negócio não ganha nenhuma linha de código de auditoria: quem
 * declara qual campo identifica o titular é o objeto de entrada (por exemplo, um
 * record de requisição).
 */
public interface AuditSubjectProvider {

    /** Identificador bruto do titular (por exemplo, o CPF). Nunca é gravado em claro. */
    String auditSubjectId();
}
