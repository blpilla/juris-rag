package br.com.jurisrag.documents;

public class PayloadTooLargeException extends RuntimeException {
    public PayloadTooLargeException() {
        super("O PDF deve ter no máximo 10 MiB.");
    }
}
