package br.com.jurisrag.retrieval;

import java.util.UUID;

public record Citation(UUID documentId, String title, int page, String excerpt) { }
