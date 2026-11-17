package br.com.jurisrag.agent;

public class InvalidModelOutputException extends RuntimeException {
    public InvalidModelOutputException(Throwable cause) {
        super("O modelo não devolveu uma saída estruturada válida.", cause);
    }
}
