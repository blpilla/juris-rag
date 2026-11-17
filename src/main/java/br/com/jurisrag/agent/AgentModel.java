package br.com.jurisrag.agent;

import br.com.jurisrag.retrieval.RetrievedChunk;
import java.time.LocalDate;
import java.util.List;

public interface AgentModel {
    Evaluation evaluate(String question, List<RetrievedChunk> evidence, String policyGap, LocalDate referenceDate);
    String rewrite(String question, String gap, List<RetrievedChunk> evidence);
    AnswerDraft answer(String question, List<RetrievedChunk> evidence);

    record Evaluation(boolean sufficient, List<String> evidenceIds, String gap, String rewriteHint) { }
    record AnswerDraft(String answer, List<String> evidenceIds) { }
}
