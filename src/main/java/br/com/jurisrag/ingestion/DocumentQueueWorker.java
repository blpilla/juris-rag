package br.com.jurisrag.ingestion;

import br.com.jurisrag.config.JurisRagProperties;
import br.com.jurisrag.documents.DocumentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.List;
import java.util.UUID;

@Component
@Profile("worker")
public class DocumentQueueWorker {
    private static final Logger log = LoggerFactory.getLogger(DocumentQueueWorker.class);
    private final SqsClient sqs;
    private final JurisRagProperties properties;
    private final DocumentRepository documents;
    private final PdfTextExtractor extractor;
    private final TextChunker chunker;
    private final EmbeddingProvider embeddings;
    private final DocumentChunkRepository chunks;
    private final ObjectMapper objectMapper;
    private final MeterRegistry metrics;
    private volatile String queueUrl;

    public DocumentQueueWorker(SqsClient sqs, JurisRagProperties properties, DocumentRepository documents,
                               PdfTextExtractor extractor, TextChunker chunker, EmbeddingProvider embeddings,
                               DocumentChunkRepository chunks, ObjectMapper objectMapper, MeterRegistry metrics) {
        this.sqs = sqs;
        this.properties = properties;
        this.documents = documents;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${juris-rag.queue.poll-delay-ms:1000}")
    public void poll() {
        String url = queueUrl();
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(url)
                        .maxNumberOfMessages(5)
                        .waitTimeSeconds(10)
                        .visibilityTimeout(properties.queue().visibilitySeconds())
                        .build())
                .messages();
        for (Message message : messages) {
            process(url, message);
        }
    }

    private void process(String url, Message message) {
        Timer.Sample timer = Timer.start(metrics);
        UUID documentId;
        try {
            documentId = UUID.fromString(objectMapper.readTree(message.body()).path("documentId").asText());
        } catch (Exception error) {
            log.warn("Mensagem inválida na fila; messageId={}", message.messageId());
            delete(url, message);
            timer.stop(metrics.timer("jurisrag.ingestion.latency"));
            return;
        }

        try {
            var document = documents.findById(documentId).orElse(null);
            if (document == null || "READY".equals(document.status()) || "FAILED".equals(document.status())) {
                delete(url, message);
                return;
            }
            List<TextChunk> extracted = chunker.split(extractor.extract(document.pdfBytes()));
            if (extracted.isEmpty()) {
                chunks.fail(documentId, "EMPTY_TEXT");
                delete(url, message);
                return;
            }
            List<float[]> vectors = embedInBatches(extracted);
            chunks.publishReady(document, extracted, vectors);
            delete(url, message);
            log.info("ingestion.completed documentId={} chunkCount={}", documentId, extracted.size());
        } catch (PdfWithoutTextLayerException error) {
            chunks.fail(documentId, "PDF_NO_TEXT_LAYER");
            delete(url, message);
            log.info("ingestion.failed documentId={} reason=PDF_NO_TEXT_LAYER", documentId);
        } catch (InvalidPdfContentException error) {
            chunks.fail(documentId, "INVALID_PDF");
            delete(url, message);
            log.info("ingestion.failed documentId={} reason=INVALID_PDF", documentId);
        } catch (RuntimeException error) {
            int retryVisibility = Math.min(30, Math.max(5, properties.queue().visibilitySeconds() / 10));
            sqs.changeMessageVisibility(ChangeMessageVisibilityRequest.builder()
                    .queueUrl(url).receiptHandle(message.receiptHandle()).visibilityTimeout(retryVisibility).build());
            log.warn("ingestion.retry documentId={} errorType={}", documentId, error.getClass().getSimpleName());
        } finally {
            timer.stop(metrics.timer("jurisrag.ingestion.latency"));
        }
    }

    private List<float[]> embedInBatches(List<TextChunk> source) {
        int batchSize = Math.max(1, properties.embedding().batchSize());
        java.util.ArrayList<float[]> vectors = new java.util.ArrayList<>(source.size());
        for (int start = 0; start < source.size(); start += batchSize) {
            int end = Math.min(start + batchSize, source.size());
            List<String> texts = source.subList(start, end).stream().map(TextChunk::content).toList();
            List<float[]> batch = embeddings.embed(texts);
            if (batch.size() != texts.size()) {
                throw new IllegalStateException("O provedor retornou quantidade inesperada de embeddings.");
            }
            if (batch.stream().anyMatch(vector -> vector.length != embeddings.dimensions())) {
                throw new IllegalStateException("O provedor retornou uma dimensão de embedding inesperada.");
            }
            vectors.addAll(batch);
        }
        return vectors;
    }

    private void delete(String url, Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(url).receiptHandle(message.receiptHandle()).build());
    }

    private String queueUrl() {
        if (queueUrl == null) {
            synchronized (this) {
                if (queueUrl == null) {
                    queueUrl = sqs.getQueueUrl(GetQueueUrlRequest.builder()
                            .queueName(properties.queue().name()).build()).queueUrl();
                }
            }
        }
        return queueUrl;
    }
}
