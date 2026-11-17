package br.com.jurisrag.config;

import br.com.jurisrag.documents.DocumentNotFoundException;
import br.com.jurisrag.documents.InvalidPdfException;
import br.com.jurisrag.documents.PayloadTooLargeException;
import br.com.jurisrag.documents.QueuePublishException;
import br.com.jurisrag.agent.InvalidModelOutputException;
import br.com.jurisrag.agent.ModelUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import software.amazon.awssdk.core.exception.SdkException;

import java.net.URI;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiProblemHandler {
    @ExceptionHandler({InvalidPdfException.class, MethodArgumentNotValidException.class,
            ResponseStatusException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingServletRequestPartException.class,
            ConstraintViolationException.class})
    ProblemDetail badRequest(Exception error) {
        if (error instanceof InvalidPdfException invalidPdf) {
            return problem(HttpStatus.BAD_REQUEST, "Requisição inválida", safeMessage(invalidPdf.getMessage()));
        }
        if (error instanceof ResponseStatusException status && status.getStatusCode().value() != 400) {
            return problem(status.getStatusCode(), "Requisição inválida", safeMessage(status.getReason()));
        }
        return problem(HttpStatus.BAD_REQUEST, "Requisição inválida", "Verifique os campos enviados.");
    }

    @ExceptionHandler({PayloadTooLargeException.class, MaxUploadSizeExceededException.class})
    ProblemDetail tooLarge(Exception error) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Arquivo excede o limite", "O PDF deve ter no máximo 10 MiB.");
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail notFound(DocumentNotFoundException error) {
        return problem(HttpStatus.NOT_FOUND, "Documento não encontrado", "O documento não existe neste tenant.");
    }

    @ExceptionHandler({ResourceAccessException.class, RestClientException.class, SdkException.class,
            QueuePublishException.class, DataAccessException.class, InvalidModelOutputException.class,
            ModelUnavailableException.class})
    ProblemDetail dependencyFailure(Exception error) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Dependência indisponível",
                "Um serviço necessário não respondeu. Tente novamente mais tarde.");
    }

    @ExceptionHandler(ErrorResponseException.class)
    ProblemDetail frameworkError(ErrorResponseException error) {
        ProblemDetail detail = error.getBody();
        detail.setType(URI.create("about:blank"));
        return detail;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail internalFailure(Exception error) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno", "Não foi possível concluir a solicitação.");
    }

    private ProblemDetail problem(org.springframework.http.HttpStatusCode status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(title);
        return problem;
    }

    private String safeMessage(String message) {
        return message == null || message.isBlank() ? "Verifique os campos enviados." : message;
    }
}
