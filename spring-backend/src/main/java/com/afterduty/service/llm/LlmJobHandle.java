package com.afterduty.service.llm;

/**
 * Opaque handle returned by {@link LlmAsyncProvider#submit}. The internal
 * UUID lets us tie back to the LlmJob row; the providerJobId is whatever
 * the vendor returned (Anthropic batch_id, internal stream uuid, etc.).
 */
public final class LlmJobHandle {

    private final java.util.UUID internalJobId;
    private final String providerJobId;

    public LlmJobHandle(java.util.UUID internalJobId, String providerJobId) {
        this.internalJobId = internalJobId;
        this.providerJobId = providerJobId;
    }

    public java.util.UUID getInternalJobId() { return internalJobId; }
    public String getProviderJobId() { return providerJobId; }
}
