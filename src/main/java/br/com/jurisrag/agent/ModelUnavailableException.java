package br.com.jurisrag.agent;

public class ModelUnavailableException extends RuntimeException {
    public ModelUnavailableException(Throwable cause) {
        super("O modelo configurado não respondeu.", cause);
    }
}
