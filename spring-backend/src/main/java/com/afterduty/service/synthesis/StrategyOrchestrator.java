package com.afterduty.service.synthesis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Strategy-optimized synthesis orchestrator.
 *
 * @deprecated Synthesis is now driven asynchronously by {@link SynthesisStateMachine}.
 * This class is retained as a stub to avoid breaking any Spring context that expects it
 * as a bean. All business logic has moved to SynthesisStateMachine.
 */
@Deprecated
@Service
public class StrategyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StrategyOrchestrator.class);

    /**
     * Run the optimized multi-strategy synthesis pipeline.
     *
     * @deprecated Use the async pipeline driven by {@link SynthesisStateMachine} instead.
     */
    @Deprecated
    public Map<String, Object> runOptimizedSynthesis(Long claimId, Long userId) {
        throw new UnsupportedOperationException(
                "StrategyOrchestrator.runOptimizedSynthesis() has been superseded by SynthesisStateMachine. " +
                "Use the async pipeline instead.");
    }
}
