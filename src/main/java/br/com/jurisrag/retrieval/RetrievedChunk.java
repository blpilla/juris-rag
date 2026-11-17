package br.com.jurisrag.retrieval;

import java.time.LocalDate;
import java.util.UUID;

public record RetrievedChunk(
        String chunkId,
        UUID documentId,
        String tenantId,
        String title,
        int page,
        int position,
        String section,
        String excerpt,
        String clauseId,
        String contractReference,
        boolean amendment,
        String linkedContractReference,
        String linkedClauseId,
        LocalDate effectiveDate,
        double score
) { }
