package br.com.jurisrag.agent;

import br.com.jurisrag.retrieval.RetrievedChunk;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Profile("deterministic")
public class DeterministicAgentModel implements AgentModel {
    private static final Pattern WORD = Pattern.compile("[\\p{L}0-9]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DAYS = Pattern.compile("(?iu)\\b\\d+\\s+dias(?:\\s+(?:corridos|úteis|uteis))?");

    @Override
    public Evaluation evaluate(String question, List<RetrievedChunk> evidence, String policyGap, LocalDate referenceDate) {
        if (evidence.isEmpty()) return new Evaluation(false, List.of(), "Nenhum trecho autorizado foi recuperado.", "");
        if (policyGap != null && !policyGap.isBlank()) return new Evaluation(false, List.of(), policyGap, policyGap);
        String normalizedQuestion = normalize(question);
        Set<String> questionTerms = new HashSet<>(terms(normalizedQuestion));
        List<String> support = new ArrayList<>();
        for (RetrievedChunk chunk : evidence) {
            String normalizedExcerpt = normalize(chunk.excerpt() + " " + (chunk.section() == null ? "" : chunk.section()));
            Set<String> excerptTerms = new HashSet<>(terms(normalizedExcerpt));
            long shared = questionTerms.stream().filter(excerptTerms::contains).count();
            if (shared >= 2 || topicMatch(normalizedQuestion, normalizedExcerpt) && shared >= 1) {
                support.add(chunk.chunkId());
            } else if (topicMatch(normalizedQuestion, normalizedExcerpt) && hasLegalSpecificity(normalizedQuestion)) {
                support.add(chunk.chunkId());
            }
        }
        if (!support.isEmpty()) return new Evaluation(true, support, "", "");
        String gap = "A busca não confirmou o instituto jurídico e a cláusula aplicável; acrescente termos contratuais específicos.";
        return new Evaluation(false, List.of(), gap, gap);
    }

    @Override
    public String rewrite(String question, String gap, List<RetrievedChunk> evidence) {
        String rewritten = question;
        rewritten = replaceAny(rewritten, List.of("encerrar", "encerra", "encerramento", "terminar", "cancelar"), "resilição unilateral");
        rewritten = replaceAny(rewritten, List.of("avisar", "aviso", "comunicar", "antecedência", "antecedencia"), "notificação prévia");
        rewritten = replaceAny(rewritten, List.of("sem motivo", "imotivado", "imotivada"), "sem justa causa");
        rewritten = replaceAny(rewritten, List.of("multa"), "penalidade contratual");
        rewritten = replaceAny(rewritten, List.of("vazamento"), "incidente de segurança");
        String clause = evidence.stream().map(RetrievedChunk::clauseId).filter(value -> value != null).findFirst().orElse(null);
        if (clause != null && !rewritten.contains(clause)) rewritten += " cláusula " + clause;
        return rewritten.strip();
    }

    @Override
    public AnswerDraft answer(String question, List<RetrievedChunk> evidence) {
        if (evidence.isEmpty()) return new AnswerDraft("Não localizei evidência suficiente no acervo consultado.", List.of());
        boolean amendment = evidence.stream().anyMatch(RetrievedChunk::amendment);
        if (amendment) {
            String current = firstDuration(evidence.stream().filter(RetrievedChunk::amendment).map(RetrievedChunk::excerpt).toList());
            String prior = firstDuration(evidence.stream().filter(chunk -> !chunk.amendment()).map(RetrievedChunk::excerpt).toList());
            if (current != null && prior != null) {
                return new AnswerDraft("O aditivo vigente reduz o prazo para " + current + "; o contrato previa " + prior + ".",
                        evidence.stream().map(RetrievedChunk::chunkId).distinct().toList());
            }
        }
        RetrievedChunk selected = evidence.getFirst();
        String answer = selected.excerpt().replaceAll("\\s+", " ").strip();
        return new AnswerDraft(answer, List.of(selected.chunkId()));
    }

    private String firstDuration(List<String> texts) {
        for (String text : texts) {
            Matcher matcher = DAYS.matcher(text);
            if (matcher.find()) return matcher.group();
        }
        return null;
    }

    private String replaceAny(String source, List<String> triggers, String replacement) {
        String result = source;
        for (String trigger : triggers) {
            result = result.replaceAll("(?iu)\\b" + Pattern.quote(trigger) + "\\b", Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    private boolean topicMatch(String query, String text) {
        boolean payment = query.matches("(?s).*(pagamento|fatura|cobranca).*") && text.matches("(?s).*(pagamento|fatura|cobranca).*");
        boolean termination = query.matches("(?s).*(encerr|termin|resili|rescis|aviso|notific).*") && text.matches("(?s).*(resili|rescis|notific|aviso).*");
        boolean penalty = query.matches("(?s).*(multa|penalidade|penalizacao).*") && text.matches("(?s).*(multa|penalidade|penalizacao).*");
        return payment || termination || penalty;
    }

    private boolean hasLegalSpecificity(String question) {
        return question.matches("(?s).*(resili|rescis|distrat|denunci|notific|clausul|vigenci).*");
    }

    private List<String> terms(String text) {
        Matcher matcher = WORD.matcher(text);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 3 && !token.chars().allMatch(Character::isDigit)
                    && !List.of("para", "com", "qual", "previsto", "contrato", "sem").contains(token)) tokens.add(token);
        }
        return tokens;
    }

    private String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
