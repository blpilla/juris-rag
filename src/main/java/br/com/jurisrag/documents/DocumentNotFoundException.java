package br.com.jurisrag.documents;

public class DocumentNotFoundException extends RuntimeException {
    public DocumentNotFoundException() {
        super("Documento não encontrado.");
    }
}
