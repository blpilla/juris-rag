package br.com.jurisrag.documents;

public class QueuePublishException extends RuntimeException {
    public QueuePublishException(Throwable cause) {
        super("Não foi possível publicar o documento na fila.", cause);
    }
}
