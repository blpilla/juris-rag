package br.com.jurisrag.documents;

import java.util.UUID;

public record UploadResponse(UUID documentId, String statusUrl) { }
