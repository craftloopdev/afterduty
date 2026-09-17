package com.afterduty.eval;

import com.afterduty.model.LlmJob;
import com.afterduty.service.llm.FakeLlmAsyncProvider;
import com.afterduty.service.llm.LlmAsyncProvider.FetchedResult;
import com.afterduty.service.llm.LlmJobHandle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for the {@link FakeLlmAsyncProvider#setResponder} extension (spec §2.3 /
 * §8.6): per-condition responses resolve, and the existing purpose/evidence
 * precedence is unchanged (per-evidence wins over responder wins over purpose text).
 */
@Tag("regression")
class FakeResponderTest {

    private LlmJob job(String purpose, Long conditionId, Long evidenceId) {
        LlmJob j = new LlmJob();
        j.setId(java.util.UUID.randomUUID());
        j.setPurpose(purpose);
        j.setConditionId(conditionId);
        j.setEvidenceId(evidenceId);
        return j;
    }

    private String fetchText(FakeLlmAsyncProvider fake, LlmJob j) {
        List<LlmJobHandle> handles = fake.submit(List.of(j));
        List<FetchedResult> results = fake.fetchResults(handles.get(0).getProviderJobId(), List.of(j));
        return results.get(0).result.getText();
    }

    @Test
    void responder_derivesResponseFromConditionId() {
        FakeLlmAsyncProvider fake = new FakeLlmAsyncProvider();
        fake.setResponder("synthesis_rate", j ->
                "{\"estimated_rating\":" + (j.getConditionId() == 1L ? 70 : 10) + "}");

        assertEquals("{\"estimated_rating\":70}", fetchText(fake, job("synthesis_rate", 1L, null)));
        assertEquals("{\"estimated_rating\":10}", fetchText(fake, job("synthesis_rate", 2L, null)));
    }

    @Test
    void responder_winsOverPurposeKeyedText() {
        FakeLlmAsyncProvider fake = new FakeLlmAsyncProvider();
        fake.setResponse("synthesis_rate", "PURPOSE_DEFAULT");
        fake.setResponder("synthesis_rate", j -> "RESPONDER");
        assertEquals("RESPONDER", fetchText(fake, job("synthesis_rate", 5L, null)));
    }

    @Test
    void perEvidence_winsOverResponder() {
        FakeLlmAsyncProvider fake = new FakeLlmAsyncProvider();
        fake.setResponder("extraction_doc", j -> "RESPONDER");
        fake.setResponseForEvidence(42L, "PER_EVIDENCE");
        assertEquals("PER_EVIDENCE", fetchText(fake, job("extraction_doc", null, 42L)));
    }

    @Test
    void responderReturningNull_fallsThroughToPurposeText() {
        FakeLlmAsyncProvider fake = new FakeLlmAsyncProvider();
        fake.setResponse("synthesis_rate", "PURPOSE_DEFAULT");
        fake.setResponder("synthesis_rate", j -> null);
        assertEquals("PURPOSE_DEFAULT", fetchText(fake, job("synthesis_rate", 9L, null)));
    }

    @Test
    void unsetPurpose_stillDefaultsToEmptyArray() {
        FakeLlmAsyncProvider fake = new FakeLlmAsyncProvider();
        assertEquals("[]", fetchText(fake, job("some_other_purpose", null, null)));
    }
}
