package br.com.jurisrag.ingestion;

public record TextChunk(int pageNumber, int position, String section, String content) { }
