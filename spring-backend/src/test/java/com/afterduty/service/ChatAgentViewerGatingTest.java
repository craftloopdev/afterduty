package com.afterduty.service;

import com.afterduty.model.Claim;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.MessageRepository;
import com.afterduty.repository.VasrdRecordRepository;
import com.afterduty.service.rag.HybridRetrievalService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-19 (Phase G1) — viewer chat is grounding-only. The mutating tools are
 * excluded from the schema sent to the model for viewer principals AND
 * independently denied at execution time (defense in depth).
 */
class ChatAgentViewerGatingTest {

    private static final Set<String> GROUNDING_TOOLS =
            Set.of("search_my_file", "get_analysis", "get_pipeline_status", "vasrd_lookup");

    private final AtomRepository atomRepo = Mockito.mock(AtomRepository.class);
    private final ConditionRepository condRepo = Mockito.mock(ConditionRepository.class);
    private final com.afterduty.repository.ClaimRepository claimRepo =
            Mockito.mock(com.afterduty.repository.ClaimRepository.class);

    private ChatAgent agent() {
        return new ChatAgent(atomRepo, condRepo,
                Mockito.mock(MessageRepository.class), Mockito.mock(AiCostService.class),
                Mockito.mock(HybridRetrievalService.class),
                Mockito.mock(VasrdRecordRepository.class), Mockito.mock(VasrdDataService.class),
                Mockito.mock(com.afterduty.repository.EvidenceRepository.class), claimRepo,
                Mockito.mock(com.afterduty.repository.ConditionSuppressionRepository.class),
                Mockito.mock(com.afterduty.repository.ServiceProfileRepository.class),
                Mockito.mock(com.afterduty.repository.UserRepository.class));
    }

    @SuppressWarnings("unchecked")
    private Set<String> schemaNames(ChatAgent a, boolean viewer) throws Exception {
        Method m = ChatAgent.class.getDeclaredMethod("buildToolSchema", boolean.class);
        m.setAccessible(true);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) m.invoke(a, viewer);
        return tools.stream().map(t -> (String) t.get("name")).collect(Collectors.toSet());
    }

    private String execTool(ChatAgent a, String name, Long claimId, Long userId) throws Exception {
        Method m = ChatAgent.class.getDeclaredMethod("executeTool",
                String.class, Map.class, Long.class, Long.class, Long.class);
        m.setAccessible(true);
        return (String) m.invoke(a, name, Map.of(), claimId, userId, 1L);
    }

    private void stubOwner(Long claimId, Long ownerId) {
        Claim c = Claim.builder().userId(ownerId).build();
        Mockito.when(claimRepo.findById(claimId)).thenReturn(Optional.of(c));
    }

    @Test
    void viewerSchema_containsOnlyGroundingTools() throws Exception {
        Set<String> viewer = schemaNames(agent(), true);
        assertThat(viewer).isEqualTo(GROUNDING_TOOLS);
        assertThat(viewer).doesNotContainAnyElementsOf(ChatAgent.MUTATING_TOOLS);
    }

    @Test
    void ownerSchema_keepsEveryMutatingTool() throws Exception {
        Set<String> owner = schemaNames(agent(), false);
        assertThat(owner).containsAll(ChatAgent.MUTATING_TOOLS);
        assertThat(owner).containsAll(GROUNDING_TOOLS);
    }

    @Test
    void executeTool_mutating_deniedForViewerPrincipal_evenIfSchemaLeaks() throws Exception {
        stubOwner(7L, 1L); // claim 7 owned by user 1
        for (String tool : ChatAgent.MUTATING_TOOLS) {
            String result = execTool(agent(), tool, 7L, 2L); // user 2 = viewer
            assertThat(result).as(tool).contains("only available to the claim owner");
        }
        // The deny path never touches the condition repo.
        Mockito.verifyNoInteractions(condRepo);
    }

    @Test
    void executeTool_mutating_notDeniedForOwner() throws Exception {
        stubOwner(7L, 1L);
        String result = execTool(agent(), "delete_condition", 7L, 1L); // owner
        assertThat(result).doesNotContain("only available to the claim owner");
    }

    @Test
    void executeTool_failsClosed_whenClaimUnknown() throws Exception {
        Mockito.when(claimRepo.findById(99L)).thenReturn(Optional.empty());
        String result = execTool(agent(), "add_atom", 99L, 1L);
        assertThat(result).contains("only available to the claim owner");
    }

    @Test
    void viewerAddendum_statesReadOnly() {
        assertThat(ChatAgent.VIEWER_MODE_ADDENDUM).contains("READ-ONLY");
        assertThat(ChatAgent.VIEWER_MODE_ADDENDUM).contains("only the veteran");
    }
}
