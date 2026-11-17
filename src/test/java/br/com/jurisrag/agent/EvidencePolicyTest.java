package br.com.jurisrag.agent;

import br.com.jurisrag.retrieval.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidencePolicyTest {
    private final EvidencePolicy policy = new EvidencePolicy();

    @Test
    void requiresBothLinkedContractAndAmendmentEvidenceWhenAmendmentIsEffective() {
        RetrievedChunk contract = chunk("contract", "Contrato 001", "90 dias corridos", "12.1", "001",
                false, null, null, null);
        RetrievedChunk amendmentCover = chunk("amendment-cover", "Aditivo 01", "Aditivo 01 ao Contrato 001",
                null, "001", true, null, null, null);
        RetrievedChunk amendment = chunk("amendment", "Aditivo 01", "passa de 90 para 60 dias corridos",
                "1.1", "001", true, "001", "12.1", LocalDate.of(2026, 10, 1));

        EvidencePolicy.PolicyCheck check = policy.check(
                "Qual a antecedência para resilir o Contrato 001?", List.of(contract, amendmentCover, amendment),
                LocalDate.of(2026, 11, 1));

        assertThat(check.hasGap()).isFalse();
        assertThat(check.requiredEvidenceIds()).containsExactlyInAnyOrder("contract", "amendment");
    }

    @Test
    void refusesAnAmendmentThatTakesEffectAfterTheReferenceDate() {
        RetrievedChunk contract = chunk("contract", "Contrato 001", "90 dias corridos", "12.1", "001",
                false, null, null, null);
        RetrievedChunk amendment = chunk("amendment", "Aditivo 01", "passa de 90 para 60 dias corridos",
                "1.1", "001", true, "001", "12.1", LocalDate.of(2026, 12, 1));

        EvidencePolicy.PolicyCheck check = policy.check(
                "Qual a antecedência para resilir o Contrato 001?", List.of(contract, amendment),
                LocalDate.of(2026, 11, 1));

        assertThat(check.hasGap()).isTrue();
        assertThat(check.requiredEvidenceIds()).isEmpty();
    }

    private RetrievedChunk chunk(String id, String title, String excerpt, String clause, String contract,
                                 boolean amendment, String linkedContract, String linkedClause,
                                 LocalDate effectiveDate) {
        return new RetrievedChunk(id, UUID.nameUUIDFromBytes(id.getBytes()), "energia-demo", title, 1, 0,
                "Cláusula " + clause, excerpt, clause, contract, amendment, linkedContract, linkedClause,
                effectiveDate, 1.0);
    }
}
