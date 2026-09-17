package com.afterduty.service.synthesis;

import com.afterduty.model.Atom;
import com.afterduty.service.llm.LlmJobRequest;
import com.afterduty.service.llm.LlmJobResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase B item B2 — identify prompt v2. Pins the two coordinated prompt changes
 * (one version bump, {@link ConditionIdentificationAgent#IDENTIFY_PROMPT_VERSION}):
 * <ol>
 *   <li>ATTRIBUTION AT IDENTIFY — the schema gains {@code supporting_atom_ids},
 *       the atom summary labels every atom with its id so the model can cite it,
 *       and {@code parseResponse} passes the field through untouched;</li>
 *   <li>PRESUMPTIVE-NEXUS RULE (P1-2's identify half) — an applicable presumption
 *       scores {@code triad_nexus} STRONG with a "[Presumptive under <basis>]"
 *       note, so PACT-Act veterans stop being steered toward paid nexus letters.</li>
 * </ol>
 * Pure unit test: buildRequest/parseResponse never touch the job service.
 */
class ConditionIdentificationAgentTest {

    private final ConditionIdentificationAgent agent = new ConditionIdentificationAgent(null);

    private static Atom atomWithId(long id, String type, String value, String source) {
        Atom a = Atom.builder().claimId(1L).evidenceId(10L)
                .type(type).value(value).source(source).confidence(0.9).build();
        a.setId(id);
        return a;
    }

    @Test
    void promptVersionIsThree_registryCoupledConstant() {
        assertEquals("3", ConditionIdentificationAgent.IDENTIFY_PROMPT_VERSION,
                "secondary-aware analysis (secondary_to + reframed triad) rides a coordinated bump to v3");
    }

    @Test
    void systemPrompt_asksForSecondaryTo_andReframesTriadForSecondaries() {
        LlmJobRequest req = agent.buildRequest(
                List.of(atomWithId(7L, "diagnosis", "GERD diagnosed", "gastro 2023")),
                "service ctx", "presumptive ctx", 1L, 2L);

        String system = req.getSystemPrompt();
        assertTrue(system.contains("secondary_to"),
                "the identify schema must request secondary_to (the primary condition's name)");
        assertTrue(system.contains("3.310"),
                "the secondary rule must cite 38 CFR 3.310");
        assertTrue(system.contains("SECONDARY SERVICE CONNECTION"),
                "the secondary carve-out must be present");
        assertTrue(system.contains("NO separate in-service event"),
                "the rule must state a secondary has no separate in-service event");
    }

    @Test
    void systemPrompt_asksForSupportingAtomIds_andPresumptiveNexusRule() {
        LlmJobRequest req = agent.buildRequest(
                List.of(atomWithId(7L, "diagnosis", "asthma diagnosed", "pulmonology 2023")),
                "service ctx", "presumptive ctx", 1L, 2L);

        String system = req.getSystemPrompt();
        assertTrue(system.contains("supporting_atom_ids"),
                "the identify schema must request per-condition supporting_atom_ids");
        assertTrue(system.contains("never invent ids"),
                "the prompt must forbid hallucinated atom ids");
        assertTrue(system.contains("PRESUMPTIVE SERVICE CONNECTION"),
                "the presumptive-nexus rule must be present");
        assertTrue(system.contains("triad_nexus as STRONG"),
                "an applicable presumption scores the nexus leg STRONG");
        assertTrue(system.contains("[Presumptive under <basis>]"),
                "the nexus evidence is noted as [Presumptive under <basis>]");
        assertTrue(system.contains("NO private medical nexus opinion"),
                "the rule must say presumptive conditions need no bought nexus letter");
    }

    @Test
    void atomSummary_labelsEveryAtomWithItsId() {
        LlmJobRequest req = agent.buildRequest(
                List.of(atomWithId(7L, "diagnosis", "asthma diagnosed", "pulmonology 2023"),
                        atomWithId(9L, "medication", "albuterol daily", "pharmacy 2024")),
                "svc", "presump", 1L, 2L);

        String user = req.getUserMessage();
        assertTrue(user.contains("[atom 7] asthma diagnosed"),
                "each atom is labeled with its id so the model can cite it");
        assertTrue(user.contains("[atom 9] albuterol daily"));
    }

    @Test
    void atomSummary_nullId_rendersNoLabel() {
        Atom unsaved = Atom.builder().claimId(1L).evidenceId(10L)
                .type("diagnosis").value("asthma diagnosed").source("clinic").confidence(0.9).build();
        LlmJobRequest req = agent.buildRequest(List.of(unsaved), "svc", "presump", 1L, 2L);
        assertFalse(req.getUserMessage().contains("[atom "),
                "an unsaved atom (null id) must not render a citable fake label");
        assertTrue(req.getUserMessage().contains("asthma diagnosed"), "the value still renders");
    }

    @Test
    void parseResponse_passesSupportingAtomIdsThrough() {
        String json = """
                [
                  {"name":"Asthma","vasrd_code":"6602","body_system":"respiratory",
                   "is_presumptive":true,"presumptive_basis":"PACT Act burn pits",
                   "supporting_atom_ids":[7,9]}
                ]
                """;
        List<Map<String, Object>> parsed = agent.parseResponse(
                new LlmJobResult(json, null, 0, 0, 0, "fake", "m"));

        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        assertEquals(List.of(7, 9), parsed.get(0).get("supporting_atom_ids"),
                "supporting_atom_ids flow through the generic map parse untouched");
    }

    // -------------------------------------------------------------------------
    // 2026-09-12 claim 24: a 32-document corpus (43,835 input tokens) produced a
    // condition list longer than the provider's 16,000-token default output
    // budget. The JSON was cut off mid-object, parse failed, and the run was
    // marked FAILED as "unparseable" — which a retry would repeat identically.
    // Identify must ask for a budget sized for a big corpus, and a cap hit must
    // be recognisable as truncation rather than reported as a bad response.
    // -------------------------------------------------------------------------

    @Test
    void buildRequest_setsAnOutputBudgetSizedForALargeCorpus() {
        LlmJobRequest req = agent.buildRequest(List.of(), "", "", 1L, 1L);
        assertEquals(ConditionIdentificationAgent.MAX_OUTPUT_TOKENS, req.getMaxTokens(),
                "identify must set its budget explicitly, never inherit the provider default");
        assertTrue(req.getMaxTokens() >= 32_768,
                "16,000 was exceeded by a 32-document corpus; the budget must at least double it");
    }

    @Test
    void wasTruncated_readsTheProviderStopReason() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode capped = om.readTree("{\"stop_reason\":\"max_tokens\"}");
        JsonNode finished = om.readTree("{\"stop_reason\":\"end_turn\"}");

        assertTrue(agent.wasTruncated(new LlmJobResult("[{\"name\":\"PT", capped, 0, 0, 0, "fake", "m")),
                "stop_reason=max_tokens is truncation");
        assertFalse(agent.wasTruncated(new LlmJobResult("[]", finished, 0, 0, 0, "fake", "m")),
                "a normal end_turn is not truncation");
        assertFalse(agent.wasTruncated(new LlmJobResult("[]", null, 0, 0, 0, "fake", "m")),
                "a result with no raw payload cannot be called truncated");
    }
}
