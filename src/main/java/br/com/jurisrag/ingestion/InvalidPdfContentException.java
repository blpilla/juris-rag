package br.com.jurisrag.ingestion;

public class InvalidPdfContentException extends RuntimeException {
    public InvalidPdfContentException(Throwable cause) {
        super("O PDF não pôde ser processado.", cause);
    }
}
