package com.afterduty.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.afterduty.model.Atom;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.repository.MessageRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Mission 6b — ChatAgent cached claim-state block coverage. The chat system prompt was
 * rebuilt every message; the restructure splits it into [frozen instructions] +
 * [claim-state block under one cache_control breakpoint] so turn 2+ in a session read
 * the unchanged state at 0.1×. Verifies:
 * <ul>
 *   <li>the claim-state block is BYTE-STABLE across calls and across repository row
 *       order (the HashMap-iteration silent invalidator is fixed);</li>
 *   <li>caching ON renders the system array with exactly one breakpoint on the state block;</li>
 *   <li>caching OFF renders today's flat string (frozen + blank line + state).</li>
 * </ul>
 */
@Tag("regression")
class ChatAgentSystemBlockTest {

    private final AtomRepository atomRepo = Mockito.mock(AtomRepository.class);
    private final ConditionRepository condRepo = Mockito.mock(ConditionRepository.class);

    private ChatAgent agent(boolean caching) throws Exception {
        ChatAgent a = new ChatAgent(atomRepo, condRepo,
                Mockito.mock(MessageRepository.class), Mockito.mock(AiCostService.class),
                Mockito.mock(com.afterduty.service.rag.HybridRetrievalService.class),
                Mockito.mock(com.afterduty.repository.VasrdRecordRepository.class),
                Mockito.mock(VasrdDataService.class),
                Mockito.mock(com.afterduty.repository.EvidenceRepository.class),
                Mockito.mock(com.afterduty.repository.ClaimRepository.class),
                Mockito.mock(com.afterduty.repository.ConditionSuppressionRepository.class),
                Mockito.mock(com.afterduty.repository.ServiceProfileRepository.class),
                Mockito.mock(com.afterduty.repository.UserRepository.class));
        Field f = ChatAgent.class.getDeclaredField("promptCachingEnabled");
        f.setAccessible(true);
        f.set(a, caching);
        return a;
    }

    private Atom atom(Long id, String type, String value) {
        Atom at = new Atom();
        at.setId(id);
        at.setType(type);
        at.setValue(value);
        at.setConfidence(0.9);
        return at;
    }

    private IdentifiedCondition cond(Long id, String name, String code, Integer rating) {
        IdentifiedCondition c = new IdentifiedCondition();
        c.setId(id);
        c.setName(name);
        c.setVasrdCode(code);
        c.setEstimatedRating(rating);
        return c;
    }

    private List<Atom> atoms() {
        List<Atom> a = new ArrayList<>();
        a.add(atom(1L, "diagnosis", "asthma"));
        a.add(atom(2L, "medication", "prednisone"));
        a.add(atom(3L, "diagnosis", "tinnitus"));
        a.add(atom(4L, "symptom", "ringing"));
        return a;
    }

    private List<IdentifiedCondition> conditions() {
        List<IdentifiedCondition> c = new ArrayList<>();
        c.add(cond(11L, "Asthma", "6602", 30));
        c.add(cond(12L, "Tinnitus", "6260", 10));
        return c;
    }

    @Test
    void claimStateBlock_isByteStable_acrossRepositoryRowOrder() throws Exception {
        ChatAgent agent = agent(true);

        // First call: one fetch order.
        when(atomRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(atoms());
        when(condRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(conditions());
        String block1 = agent.buildClaimStateBlock(1L);

        // Second call: SHUFFLED fetch order (repo order is not contractually stable).
        List<Atom> shuffledAtoms = new ArrayList<>(atoms());
        Collections.reverse(shuffledAtoms);
        List<IdentifiedCondition> shuffledConds = new ArrayList<>(conditions());
        Collections.reverse(shuffledConds);
        when(atomRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(shuffledAtoms);
        when(condRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(shuffledConds);
        String block2 = agent.buildClaimStateBlock(1L);

        assertEquals(block1, block2,
                "the claim-state block must be byte-identical regardless of repository row order "
                        + "(a HashMap-iteration invalidator would make every chat turn re-pay the cache write)");
    }

    @Test
    void cachingOn_systemIsArray_withOneBreakpointOnStateBlock() throws Exception {
        ChatAgent agent = agent(true);
        when(atomRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(atoms());
        when(condRepo.findByClaimIdAndSupersededByIsNull(anyLong())).thenReturn(conditions());

        String frozen = "FROZEN INSTRUCTIONS";
        String state = agent.buildClaimStateBlock(1L);
        JsonNode sys = agent.buildSystemNode(frozen, state);

        assertTrue(sys.isArray(), "caching-on chat system must be the array form");
        assertEquals(2, sys.size());
        assertEquals(frozen, sys.get(0).path("text").asText());
        assertTrue(sys.get(0).path("cache_control").isMissingNode(), "frozen head carries no breakpoint");
        assertEquals(state, sys.get(1).path("text").asText());
        assertEquals("ephemeral", sys.get(1).path("cache_control").path("type").asText());

        long bp = 0;
        for (JsonNode b : sys) if (!b.path("cache_control").isMissingNode()) bp++;
        assertEquals(1, bp, "exactly one cache_control breakpoint, on the claim-state block");
    }

    @Test
    void flagOff_systemIsFlatString_frozenThenBlankLineThenState() throws Exception {
        ChatAgent agent = agent(false);
        String frozen = "FROZEN INSTRUCTIONS";
        String state = "CURRENT CLAIM STATE\n...";

        JsonNode sys = agent.buildSystemNode(frozen, state);
        assertTrue(sys.isTextual(), "flag-off chat system must be a plain string (today's bytes)");
        assertEquals(frozen + "\n\n" + state, sys.asText());
        assertFalse(sys.toString().contains("cache_control"), "flag-off must never emit cache_control");
    }
}
