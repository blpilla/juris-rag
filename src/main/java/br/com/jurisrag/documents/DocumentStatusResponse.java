package br.com.jurisrag.documents;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentStatusResponse(UUID documentId, String title, String status, OffsetDateTime updatedAt) { }
