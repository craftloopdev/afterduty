package com.afterduty.service.rag;

import java.time.LocalDate;

/**
 * One hybrid-retrieval hit (Increment 7, spec §D). The frozen return shape the chat
 * agent's grounding tools render into numbered, citable blocks.
 *
 * @param score the RRF-fused score (or the single-arm rank score in degraded modes)
 */
public record RetrievedChunk(
        Long id,
        String scope,
        Long claimId,
        Long evidenceId,
        String kbSource,
        String cfrSection,
        String source,
        String docType,
        String docDate,
        LocalDate asOfDate,
        String sectionPath,
        String content,
        double score
) {}
