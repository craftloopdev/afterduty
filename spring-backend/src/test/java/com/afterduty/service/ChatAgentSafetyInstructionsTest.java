package com.afterduty.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-15 — the chat system prompt is a product-level safety surface for a
 * PTSD/MST-adjacent audience. The FROZEN instructions block (the cache-stable
 * head of the system prefix — byte-identical every turn, so adding this costs
 * one cache re-write per deploy, not per message) must carry:
 * <ul>
 *   <li>the Veterans Crisis Line with both contact paths (988 press 1 / text 838255);</li>
 *   <li>the no-medical/legal-advice boundary;</li>
 *   <li>the never-promise-outcomes rule;</li>
 *   <li>the refer-filing-decisions-to-a-VSO rule.</li>
 * </ul>
 */
@Tag("regression")
class ChatAgentSafetyInstructionsTest {

    @Test
    void frozenInstructions_containVeteransCrisisLine_bothContactPaths() {
        String frozen = ChatAgent.FROZEN_INSTRUCTIONS;
        assertTrue(frozen.contains("Veterans Crisis Line"));
        assertTrue(frozen.contains("988"), "crisis line phone path (988, press 1) must be present");
        assertTrue(frozen.contains("press 1"));
        assertTrue(frozen.contains("838255"), "crisis line text path (838255) must be present");
    }

    @Test
    void frozenInstructions_containAdviceBoundaries() {
        String frozen = ChatAgent.FROZEN_INSTRUCTIONS;
        assertTrue(frozen.contains("never give medical or legal advice"));
        assertTrue(frozen.contains("Never promise or predict an outcome"));
        assertTrue(frozen.contains("Veterans Service Officer"),
                "filing decisions must be referred to an accredited VSO");
    }

    @Test
    void frozenInstructions_stayCacheStable_noPerRequestContent() {
        // The frozen block is the stable head of the cached system prefix; any
        // formatting placeholder or timestamp here would invalidate the prompt
        // cache on every message.
        String frozen = ChatAgent.FROZEN_INSTRUCTIONS;
        assertFalse(frozen.contains("%s"), "no format placeholders in the frozen block");
        assertFalse(frozen.contains("{0}"), "no MessageFormat placeholders in the frozen block");
    }
}
