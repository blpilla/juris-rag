package br.com.jurisrag.agent;

import br.com.jurisrag.retrieval.Citation;
import java.util.List;

public record QueryResponse(String status, String answer, List<Citation> citations, int attempts, boolean rewritten) { }
