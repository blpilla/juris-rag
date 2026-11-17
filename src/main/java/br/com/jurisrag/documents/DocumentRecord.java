package br.com.jurisrag.documents;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentRecord(UUID documentId, String tenantId, String title, String sha256,
                             byte[] pdfBytes, String status, OffsetDateTime updatedAt) {
    public boolean isPending() {
        return "PENDING".equals(status);
    }
}
