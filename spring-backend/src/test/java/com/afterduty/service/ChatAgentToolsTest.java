package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.VasrdRecord;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.HybridRetrievalService;
import com.afterduty.service.rag.RetrievedChunk;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Increment 7 §E.1 — the three new grounding/read tools on ChatAgent. Pure Mockito over the
 * frozen {@link HybridRetrievalService} contract + in-memory repos. Verifies each tool
 * dispatches with the right scope/claimId, formats results with the citation-relevant
 * stamps ([doc:N], "as of {date}"), never crosses claims, and that the rag-disabled flag
 * degrades the right tools to the unavailable message while keeping the schema registered.
 */
@Tag("regression")
class ChatAgentToolsTest {

    private final AtomRepository atomRepo = Mockito.mock(AtomRepository.class);
    private final ConditionRepository condRepo = Mockito.mock(ConditionRepository.class);
    private final HybridRetrievalService retrieval = Mockito.mock(HybridRetrievalService.class);
    private final VasrdRecordRepository vasrdRepo = Mockito.mock(VasrdRecordRepository.class);
    private final VasrdDataService vasrdData = Mockito.mock(VasrdDataService.class);
    private final com.afterduty.repository.EvidenceRepository evidenceRepo =
            Mockito.mock(com.afterduty.repository.EvidenceRepository.class);
    private final com.afterduty.repository.ClaimRepository claimRepo =
            Mockito.mock(com.afterduty.repository.ClaimRepository.class);
    private final com.afterduty.repository.ConditionSuppressionRepository suppressionRepo =
            Mockito.mock(com.afterduty.repository.ConditionSuppressionRepository.class);

    private ChatAgent agent(boolean ragEnabled) throws Exception {
        // P1-19 owner default-stub (executeTool guards mutating tools by ownership).
        Claim ownerClaim = new Claim();
        ownerClaim.setId(7L);
        ownerClaim.setUserId(1L);
        Mockito.when(claimRepo.findById(7L)).thenReturn(Optional.of(ownerClaim));
        ChatAgent a = new ChatAgent(atomRepo, condRepo,
                Mockito.mock(MessageRepository.class), Mockito.mock(AiCostService.class),
                retrieval, vasrdRepo, vasrdData, evidenceRepo, claimRepo, suppressionRepo,
                Mockito.mock(com.afterduty.repository.ServiceProfileRepository.class),
                Mockito.mock(com.afterduty.repository.UserRepository.class));
        Field f = ChatAgent.class.getDeclaredField("ragEnabled");
        f.setAccessible(true);
        f.set(a, ragEnabled);
        return a;
    }

    /** Invoke the private executeTool via reflection (matches the existing reflection test style). */
    private String execTool(ChatAgent a, String name, Map<String, Object> input,
                            Long claimId, Long userId, Long userMessageId) throws Exception {
        Method m = ChatAgent.class.getDeclaredMethod("executeTool",
                String.class, Map.class, Long.class, Long.class, Long.class);
        m.setAccessible(true);
        return (String) m.invoke(a, name, input, claimId, userId, userMessageId);
    }

    private RetrievedChunk evidenceChunk(Long evidenceId, String source, String docDate,
                                         String sectionPath, String content) {
        return new RetrievedChunk(1L, "evidence", 7L, evidenceId, null, null,
                source, "exam", docDate, null, sectionPath, content, 0.9);
    }

    private RetrievedChunk kbChunk(String cfrSection, LocalDate asOf, String content) {
        return new RetrievedChunk(2L, "kb", null, null, "vasrd", cfrSection,
                "ecfr", "regulation", null, asOf, "heading", content, 0.8);
    }

    // ---- search_my_file ----

    @Test
    void searchMyFile_scopesToClaim_andFormatsDocCitationBlocks() throws Exception {
        ChatAgent a = agent(true);
        when(retrieval.searchEvidence(eq(7L), eq("knee"), anyInt()))
                .thenReturn(List.of(evidenceChunk(42L, "C&P Exam.pdf", "2019-03-14",
                        "Range of Motion", "Flexion limited to 30 degrees.")));

        String out = execTool(a, "search_my_file", Map.of("query", "knee", "k", 5), 7L, 1L, 100L);

        // claim_id 7 bound (retrieval enforces both-arm isolation); block carries [doc:42] + date.
        verify(retrieval).searchEvidence(eq(7L), eq("knee"), anyInt());
        assertThat(out).contains("[doc:42]").contains("C&P Exam.pdf").contains("2019-03-14")
                .contains("Range of Motion").contains("Flexion limited to 30 degrees.");
    }

    @Test
    void searchMyFile_neverReceivesAnotherClaimsId() throws Exception {
        ChatAgent a = agent(true);
        when(retrieval.searchEvidence(anyLong(), anyString(), anyInt())).thenReturn(List.of());

        execTool(a, "search_my_file", Map.of("query", "back pain"), 99L, 1L, 100L);

        ArgumentCaptor<Long> claimCap = ArgumentCaptor.forClass(Long.class);
        verify(retrieval).searchEvidence(claimCap.capture(), eq("back pain"), anyInt());
        assertThat(claimCap.getValue()).isEqualTo(99L);
    }

    @Test
    void searchMyFile_ragDisabled_returnsUnavailableMessage_andDoesNotQuery() throws Exception {
        ChatAgent a = agent(false);

        String out = execTool(a, "search_my_file", Map.of("query", "x"), 7L, 1L, 100L);

        assertThat(out).isEqualTo("Document search is not available right now.");
        verify(retrieval, never()).searchEvidence(anyLong(), anyString(), anyInt());
    }

    @Test
    void searchMyFile_noHits_saysNotFound() throws Exception {
        ChatAgent a = agent(true);
        when(retrieval.searchEvidence(eq(7L), anyString(), anyInt())).thenReturn(List.of());

        String out = execTool(a, "search_my_file", Map.of("query", "ankle"), 7L, 1L, 100L);
        assertThat(out).contains("No passages found").contains("ankle");
    }

    // ---- get_analysis ----

    @Test
    void getAnalysis_rendersLiveConditions() throws Exception {
        ChatAgent a = agent(true);
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(11L);
        c.setName("Tinnitus");
        c.setVasrdCode("6260");
        c.setEstimatedRating(10);
        when(condRepo.findByClaimIdAndSupersededByIsNull(7L)).thenReturn(List.of(c));

        String out = execTool(a, "get_analysis", Map.of(), 7L, 1L, 100L);

        // Reads the LIVE (non-superseded) generation, reusing renderConditions.
        verify(condRepo).findByClaimIdAndSupersededByIsNull(7L);
        assertThat(out).contains("Current analysis").contains("Tinnitus").contains("6260");
    }

    // ---- vasrd_lookup ----

    @Test
    void vasrdLookup_byDcCode_rendersStructuredTiersWithAsOfDate() throws Exception {
        ChatAgent a = agent(true);
        VasrdRecord r10 = VasrdRecord.builder().dcCode("5260").title("Leg, limitation of flexion")
                .cfrSection("4.71a").ratingPct(10).criteriaText("Flexion limited to 45 degrees")
                .asOfDate(LocalDate.of(2026, 6, 9)).displayOrder(1).build();
        VasrdRecord r20 = VasrdRecord.builder().dcCode("5260").title("Leg, limitation of flexion")
                .cfrSection("4.71a").ratingPct(20).criteriaText("Flexion limited to 30 degrees")
                .asOfDate(LocalDate.of(2026, 6, 9)).displayOrder(2).build();
        when(vasrdRepo.findByDcCodeOrderByDisplayOrder("5260")).thenReturn(List.of(r10, r20));

        String out = execTool(a, "vasrd_lookup", Map.of("dc_code", "5260"), 7L, 1L, 100L);

        assertThat(out).contains("DC 5260").contains("38 CFR § 4.71a")
                .contains("current as of 2026-06-09")
                .contains("10% — Flexion limited to 45 degrees")
                .contains("20% — Flexion limited to 30 degrees");
        // DB-first: did NOT fall back to JSON.
        verify(vasrdData, never()).getByCode(anyString());
    }

    // Adversarial-review minor (citation-freshness, goal #2): when a dc_code's tiers were
    // ingested at different as_of_date / cfr_section (e.g. a partial nightly re-ingest where
    // delete-by-cfr_section ran for one section but the code also appears under another),
    // the header must NOT stamp a single row-0 freshness date over every tier — each tier
    // gets its own per-tier stamp so the model can't cite a wrong "current as of" for a
    // subset of tiers.
    @Test
    void vasrdLookup_byDcCode_mixedAsOfAcrossTiers_stampsEachTierIndividually() throws Exception {
        ChatAgent a = agent(true);
        VasrdRecord fresh = VasrdRecord.builder().dcCode("5260").title("Leg, limitation of flexion")
                .cfrSection("4.71a").ratingPct(10).criteriaText("Flexion limited to 45 degrees")
                .asOfDate(LocalDate.of(2026, 6, 9)).displayOrder(1).build();
        VasrdRecord stale = VasrdRecord.builder().dcCode("5260").title("Leg, limitation of flexion")
                .cfrSection("4.71a").ratingPct(20).criteriaText("Flexion limited to 30 degrees")
                .asOfDate(LocalDate.of(2025, 1, 1)).displayOrder(2).build();
        when(vasrdRepo.findByDcCodeOrderByDisplayOrder("5260")).thenReturn(List.of(fresh, stale));

        String out = execTool(a, "vasrd_lookup", Map.of("dc_code", "5260"), 7L, 1L, 100L);

        // No single misleading header date; both tier dates are present per-tier.
        assertThat(out).doesNotContain("current as of 2026-06-09");
        assertThat(out).contains("freshness varies by tier");
        assertThat(out).contains("10% — Flexion limited to 45 degrees")
                .contains("as of 2026-06-09");
        assertThat(out).contains("20% — Flexion limited to 30 degrees")
                .contains("as of 2025-01-01");
    }

    @Test
    void vasrdLookup_byDcCode_fallsBackToJson_whenTableEmpty() throws Exception {
        ChatAgent a = agent(true);
        when(vasrdRepo.findByDcCodeOrderByDisplayOrder("9411")).thenReturn(List.of());
        when(vasrdData.getByCode("9411")).thenReturn(java.util.Optional.of(Map.of(
                "code", "9411", "name", "PTSD", "cfr_section", "4.130",
                "rating_criteria", "70%: Occupational and social impairment")));

        String out = execTool(a, "vasrd_lookup", Map.of("dc_code", "9411"), 7L, 1L, 100L);

        assertThat(out).contains("DC 9411").contains("PTSD").contains("4.130")
                .contains("Occupational and social impairment");
        verify(vasrdData).getByCode("9411");
    }

    @Test
    void vasrdLookup_byQuery_searchesKbBothSources_withAsOfStamp() throws Exception {
        ChatAgent a = agent(true);
        when(retrieval.searchKb(eq("presumptive agent orange"), isNull(), anyInt()))
                .thenReturn(List.of(kbChunk("3.309", LocalDate.of(2026, 6, 9),
                        "Diseases associated with exposure to certain herbicide agents.")));

        String out = execTool(a, "vasrd_lookup",
                Map.of("query", "presumptive agent orange"), 7L, 1L, 100L);

        // searchKb called with kbSource=null → both vasrd and presumptives.
        verify(retrieval).searchKb(eq("presumptive agent orange"), isNull(), anyInt());
        assertThat(out).contains("38 CFR § 3.309").contains("as of 2026-06-09")
                .contains("herbicide agents");
    }

    @Test
    void vasrdLookup_queryMode_ragDisabled_returnsUnavailable() throws Exception {
        ChatAgent a = agent(false);

        String out = execTool(a, "vasrd_lookup", Map.of("query", "ptsd criteria"), 7L, 1L, 100L);

        assertThat(out).isEqualTo("Document search is not available right now.");
        verify(retrieval, never()).searchKb(anyString(), anyString(), anyInt());
    }

    @Test
    void vasrdLookup_dcCodeMode_worksEvenWhenRagDisabled() throws Exception {
        // Structured lookup is DB-backed, not retrieval — must work with rag off.
        ChatAgent a = agent(false);
        VasrdRecord r = VasrdRecord.builder().dcCode("5260").cfrSection("4.71a")
                .ratingPct(10).criteriaText("Flexion limited to 45 degrees")
                .asOfDate(LocalDate.of(2026, 6, 9)).displayOrder(1).build();
        when(vasrdRepo.findByDcCodeOrderByDisplayOrder("5260")).thenReturn(List.of(r));

        String out = execTool(a, "vasrd_lookup", Map.of("dc_code", "5260"), 7L, 1L, 100L);
        assertThat(out).contains("DC 5260").contains("10% — Flexion limited to 45 degrees");
    }

    // ---- tool schema registration (stable across the rag flag) ----

    @Test
    @SuppressWarnings("unchecked")
    void toolSchema_registersAllTools_regardlessOfRagFlag() throws Exception {
        Method m = ChatAgent.class.getDeclaredMethod("buildToolSchema");
        m.setAccessible(true);

        List<Map<String, Object>> onTools = (List<Map<String, Object>>) m.invoke(agent(true));
        List<Map<String, Object>> offTools = (List<Map<String, Object>>) m.invoke(agent(false));

        List<String> onNames = onTools.stream().map(t -> (String) t.get("name")).toList();
        List<String> offNames = offTools.stream().map(t -> (String) t.get("name")).toList();

        assertThat(onNames).containsExactlyElementsOf(offNames); // schema stable across the flag
        assertThat(onNames).contains("add_atom", "update_atom", "delete_atom",
                "update_condition", "delete_condition", "restore_condition",
                "mark_gap_resolved", "mark_gap_dismissed",
                "search_my_file", "get_analysis", "get_pipeline_status", "vasrd_lookup");
    }

    // ---- mutation-tool regression (existing behavior must still dispatch) ----

    @Test
    void mutationTool_addAtom_stillCreatesAtom() throws Exception {
        ChatAgent a = agent(true);
        com.afterduty.model.Atom saved = new com.afterduty.model.Atom();
        saved.setId(55L);
        when(atomRepo.save(Mockito.any())).thenReturn(saved);

        String out = execTool(a, "add_atom",
                Map.of("atom_type", "diagnosis", "value", "tinnitus"), 7L, 1L, 100L);

        verify(atomRepo).save(Mockito.any());
        assertThat(out).contains("Atom #55 created");
    }
}
