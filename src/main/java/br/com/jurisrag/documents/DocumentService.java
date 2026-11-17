package br.com.jurisrag.documents;

import br.com.jurisrag.config.JurisRagProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {
    private static final int MAX_PDF_BYTES = 10 * 1024 * 1024;
    private final DocumentRepository documents;
    private final SqsClient sqs;
    private final JurisRagProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private volatile String queueUrl;

    public DocumentService(DocumentRepository documents, SqsClient sqs, JurisRagProperties properties,
                           TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.documents = documents;
        this.sqs = sqs;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public UploadResponse upload(String tenantId, String title, MultipartFile file) {
        if (title == null || title.isBlank()) {
            throw new InvalidPdfException("O título é obrigatório.");
        }
        String safeTitle = title.trim();
        if (safeTitle.length() > 200) {
            throw new InvalidPdfException("O título deve ter até 200 caracteres.");
        }
        if (file == null || file.isEmpty()) {
            throw new InvalidPdfException("O arquivo PDF é obrigatório.");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new PayloadTooLargeException();
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw new InvalidPdfException("Não foi possível ler o arquivo enviado.");
        }
        if (bytes.length > MAX_PDF_BYTES) {
            throw new PayloadTooLargeException();
        }
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') {
            throw new InvalidPdfException("A assinatura do arquivo não corresponde a um PDF.");
        }

        String sha256 = sha256(bytes);
        DocumentRecord document = transactionTemplate.execute(status ->
                documents.createOrFind(tenantId, safeTitle, sha256, bytes));
        if (document == null) {
            throw new IllegalStateException("A transação do documento não retornou um resultado.");
        }
        if (document.isPending()) {
            sendForIngestion(document.documentId());
        }
        return new UploadResponse(document.documentId(), "/v1/documents/" + document.documentId());
    }

    public DocumentStatusResponse status(String tenantId, UUID documentId) {
        DocumentRecord document = documents.findByTenantAndId(tenantId, documentId)
                .orElseThrow(DocumentNotFoundException::new);
        return new DocumentStatusResponse(document.documentId(), document.title(), document.status(), document.updatedAt());
    }

    private void sendForIngestion(UUID documentId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("documentId", documentId.toString()));
            String url = queueUrl;
            if (url == null) {
                synchronized (this) {
                    url = queueUrl;
                    if (url == null) {
                        url = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                                .queueName(properties.queue().name()).build()).queueUrl();
                        queueUrl = url;
                    }
                }
            }
            sqs.sendMessage(SendMessageRequest.builder().queueUrl(url).messageBody(body).build());
        } catch (JsonProcessingException | RuntimeException error) {
            throw new QueuePublishException(error);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 indisponível.", error);
        }
    }
}
