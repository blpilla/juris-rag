package br.com.jurisrag.agent;

import br.com.jurisrag.retrieval.RetrievedChunk;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConsultationState extends AgentState {
    public static final String QUERY_ID = "queryId";
    public static final String TENANT = "tenantId";
    public static final String ORIGINAL = "originalQuestion";
    public static final String CURRENT = "currentQuery";
    public static final String ATTEMPT = "attempt";
    public static final String REWRITTEN = "rewritten";
    public static final String GAP = "gap";
    public static final String EVIDENCE = "evidence";
    public static final String HISTORY = "retrievedIdsByAttempt";
    public static final String APPROVED_IDS = "approvedEvidenceIds";
    public static final String REQUIRED_IDS = "requiredEvidenceIds";
    public static final String CITATION_IDS = "citationIds";
    public static final String ANSWER = "answer";
    public static final String STATUS = "status";

    public static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
            Map.entry(QUERY_ID, Channels.base(() -> "")),
            Map.entry(TENANT, Channels.base(() -> "")),
            Map.entry(ORIGINAL, Channels.base(() -> "")),
            Map.entry(CURRENT, Channels.base(() -> "")),
            Map.entry(ATTEMPT, Channels.base(() -> 0)),
            Map.entry(REWRITTEN, Channels.base(() -> false)),
            Map.entry(GAP, Channels.base(() -> "")),
            Map.entry(EVIDENCE, Channels.<List<RetrievedChunk>>base(List::of)),
            Map.entry(HISTORY, Channels.<Map<Integer, List<String>>>base(HashMap::new)),
            Map.entry(APPROVED_IDS, Channels.<List<String>>base(List::of)),
            Map.entry(REQUIRED_IDS, Channels.<List<String>>base(List::of)),
            Map.entry(CITATION_IDS, Channels.<List<String>>base(List::of)),
            Map.entry(ANSWER, Channels.base(() -> "")),
            Map.entry(STATUS, Channels.base(() -> ""))
    );

    public ConsultationState(Map<String, Object> initData) { super(initData); }

    public String string(String key) { return this.<String>value(key).orElse(""); }
    public int attempt() { return this.<Integer>value(ATTEMPT).orElse(0); }
    public boolean rewritten() { return this.<Boolean>value(REWRITTEN).orElse(false); }
    public List<RetrievedChunk> evidence() { return this.<List<RetrievedChunk>>value(EVIDENCE).orElse(List.of()); }
    public List<String> approvedIds() { return this.<List<String>>value(APPROVED_IDS).orElse(List.of()); }
    public List<String> requiredIds() { return this.<List<String>>value(REQUIRED_IDS).orElse(List.of()); }
    public List<String> citationIds() { return this.<List<String>>value(CITATION_IDS).orElse(List.of()); }
    public Map<Integer, List<String>> history() { return this.<Map<Integer, List<String>>>value(HISTORY).orElse(Map.of()); }
}
