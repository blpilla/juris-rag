package br.com.jurisrag.agent;

import br.com.jurisrag.retrieval.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EvidencePolicy {
    private static final Pattern CONTRACT = Pattern.compile("(?iu)contrato\\s+(?:n[ºo.]?\\s*)?([0-9]{3})");
    private static final Pattern DURATION = Pattern.compile("(?iu)\\b(\\d+)\\s+dias(?:\\s+(?:corridos|úteis|uteis))?");

    public PolicyCheck check(String question, List<RetrievedChunk> evidence, LocalDate referenceDate) {
        if (evidence.isEmpty()) return new PolicyCheck("Nenhum trecho publicado foi recuperado.", List.of());
        String normalized = normalize(question);
        boolean deadlineChangeQuestion = isLegalChangeQuery(normalized);
        if (!deadlineChangeQuestion) return new PolicyCheck("", List.of());
        if (!hasLegalSpecificity(normalized)) {
            return new PolicyCheck("A consulta descreve uma mudança de prazo sem identificar o instituto ou a notificação contratual; reformule com os termos jurídicos presentes nos documentos.", List.of());
        }

        String expectedContract = firstGroup(CONTRACT, question);
        List<RetrievedChunk> originals = evidence.stream().filter(chunk -> !chunk.amendment())
                .filter(chunk -> duration(chunk.excerpt()) != null).toList();
        List<RetrievedChunk> amendments = evidence.stream().filter(RetrievedChunk::amendment).toList();

        if (originals.isEmpty()) {
            return new PolicyCheck("A busca ainda não trouxe a cláusula-base do contrato para validar eventual alteração.", List.of());
        }
        if (amendments.isEmpty()) {
            return new PolicyCheck("A cláusula-base indica um prazo, mas falta localizar o aditivo e confirmar a vigência atual.", List.of());
        }

        List<String> requiredIds = new ArrayList<>();
        boolean incompleteLink = false;
        boolean futureAmendment = false;
        for (RetrievedChunk amendment : amendments) {
            if (expectedContract == null) {
                incompleteLink = true;
                continue;
            }
            if (amendment.linkedContractReference() == null || amendment.linkedClauseId() == null
                    || amendment.effectiveDate() == null) {
                incompleteLink = true;
                continue;
            }
            if (!expectedContract.equals(amendment.linkedContractReference())) continue;
            RetrievedChunk matchingOriginal = originals.stream()
                    .filter(original -> amendment.linkedClauseId().equals(original.clauseId()))
                    .filter(original -> expectedContract.equals(original.contractReference()))
                    .findFirst().orElse(null);
            if (matchingOriginal == null) continue;
            if (amendment.effectiveDate().isAfter(referenceDate)) {
                futureAmendment = true;
                continue;
            }
            requiredIds.add(matchingOriginal.chunkId());
            requiredIds.add(amendment.chunkId());
        }
        if (requiredIds.isEmpty()) {
            if (futureAmendment) {
                return new PolicyCheck("A vigência do aditivo começa depois da data de referência.", List.of());
            }
            if (incompleteLink) {
                return new PolicyCheck("O vínculo do aditivo com contrato/cláusula ou a data de vigência está ausente ou ambíguo.", List.of());
            }
            return new PolicyCheck("Não foi possível vincular o aditivo ao contrato citado.", List.of());
        }
        return new PolicyCheck("", requiredIds.stream().distinct().toList());
    }

    public boolean isLegalChangeQuery(String question) {
        return normalize(question).matches("(?s).*(resili|rescis|encerr|termin|aviso|notific|antecedencia|aditiv|alter|atualiz|vigente).*");
    }

    private boolean hasLegalSpecificity(String question) {
        return question.matches("(?s).*(resili|rescis|distrat|denunci|notific|clausul|vigenci|aditiv|alter).*");
    }

    private String duration(String text) {
        Matcher matcher = DURATION.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public record PolicyCheck(String gap, List<String> requiredEvidenceIds) {
        public boolean hasGap() { return gap != null && !gap.isBlank(); }
    }
}
