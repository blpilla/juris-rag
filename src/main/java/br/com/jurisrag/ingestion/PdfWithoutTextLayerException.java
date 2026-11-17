package br.com.jurisrag.ingestion;

public class PdfWithoutTextLayerException extends RuntimeException {
    public PdfWithoutTextLayerException() {
        super("O PDF não possui camada de texto extraível.");
    }
}
