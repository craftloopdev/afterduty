package com.afterduty.service.llm;

import com.afterduty.model.LlmJob;
import com.afterduty.repository.LlmJobRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the {@link LlmJobService} surface: submit, status transitions observed
 * through getResult/allSucceeded/allTerminal, and the LlmJobFailedException path.
 *
 * Uses @DataJpaTest (H2) for real repository writes. FakeLlmAsyncProvider is
 * registered via a minimal LlmProviderRouter. LlmJobSubmitter and LlmJobPoller
 * are called directly (tick()) so we control exactly when transitions happen.
 */
@DataJpaTest
@Import({LlmJobService.class, LlmJobSubmitter.class, LlmJobPoller.class,
         LlmProviderRouterTestConfig.class})
@TestPropertySource(properties = {
        "va-claim.llm.submitter.batch-size=50",
        "va-claim.llm.submitter.poll-ms=9999999",
        "va-claim.llm.submitter.initial-delay-ms=9999999",
        "va-claim.llm.poller.poll-ms=9999999",
        "va-claim.llm.poller.initial-delay-ms=9999999",
        "va-claim.llm.orphan-deadline-min=30"
})
class LlmJobServiceTest {

    @Autowired
    LlmJobService llmJobService;

    @Autowired
    LlmJobSubmitter llmJobSubmitter;

    @Autowired
    LlmJobPoller llmJobPoller;

    @Autowired
    LlmJobRepository llmJobRepository;

    @Autowired
    FakeLlmAsyncProvider fakeProvider;

    @BeforeEach
    void setUp() {
        fakeProvider.reset();
        fakeProvider.setResponse("synthesis_identify",
                "[{\"name\":\"PTSD\",\"vasrd_code\":\"9411\",\"body_system\":\"mental\"}]");
    }

    // -----------------------------------------------------------------------
    // Scenario 1: submit_persistsQueuedRow
    // -----------------------------------------------------------------------

    @Test
    void submit_serializesStructuredPromptIntoRequestPayload_onlyWhenSet() throws Exception {
        // With a structured prompt: request_payload carries systemBlocks + cachedCorpus.
        LlmJobRequest withSp = LlmJobRequest.builder()
                .purpose("synthesis_identify")
                .userMessage("Identify conditions.")
                .structuredPrompt(new LlmJobRequest.StructuredPrompt(
                        List.of("Stable rules.", "VASRD slice."), "ATOM CORPUS"))
                .claimId(1L).userId(100L).build();
        UUID id = llmJobService.submit(withSp);
        com.fasterxml.jackson.databind.JsonNode payload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(llmJobRepository.findById(id).orElseThrow().getRequestPayload());
        com.fasterxml.jackson.databind.JsonNode sp = payload.path("structuredPrompt");
        assertFalse(sp.isMissingNode(), "structuredPrompt must be serialized when set");
        assertEquals("ATOM CORPUS", sp.path("cachedCorpus").asText());
        assertEquals(2, sp.path("systemBlocks").size());

        // Without one (legacy path): the key is absent → byte-identical to pre-6a payloads.
        LlmJobRequest legacy = LlmJobRequest.builder()
                .purpose("synthesis_identify").userMessage("Identify.").claimId(1L).userId(100L).build();
        UUID legacyId = llmJobService.submit(legacy);
        com.fasterxml.jackson.databind.JsonNode legacyPayload = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(llmJobRepository.findById(legacyId).orElseThrow().getRequestPayload());
        assertTrue(legacyPayload.path("structuredPrompt").isMissingNode(),
                "legacy request must not carry a structuredPrompt key");
    }

    @Test
    void resultPayload_roundTripsCacheTokens() {
        // The poller persists r → response_payload via serializeResultPayload, then
        // LlmJobService deserializes it; cache token counts must survive that trip.
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        LlmJobResult original = new LlmJobResult(
                "text", om.createObjectNode(), 1200L, 300L, 0L,
                /*cacheRead*/ 60000L, /*cacheWrite*/ 5000L, "vertex-anthropic", "claude-sonnet-4-6");
        String payload = LlmJobService.serializeResultPayload(original, om);
        assertTrue(payload.contains("cacheReadTokens"));
        assertTrue(payload.contains("cacheWriteTokens"));

        // Persist a SUCCEEDED job carrying that payload and read it back through getResult.
        LlmJob job = llmJobRepository.save(LlmJob.builder()
                .provider("vertex-anthropic").modelName("claude-sonnet-4-6")
                .purpose("gap_evidence").status(LlmJob.Status.SUCCEEDED)
                .claimId(5L).userId(1L)
                .requestPayload("{\"purpose\":\"gap_evidence\",\"userMessage\":\"x\"}")
                .responsePayload(payload)
                .completedAt(Instant.now()).build());
        LlmJobResult restored = llmJobService.getResult(job.getId()).orElseThrow();
        assertEquals(60000L, restored.getCacheReadTokens());
        assertEquals(5000L, restored.getCacheWriteTokens());
    }

    @Test
    void submit_persistsQueuedRow() {
        LlmJobRequest req = LlmJobRequest.builder()
                .purpose("synthesis_identify")
                .userMessage("Identify conditions.")
                .claimId(1L)
                .userId(100L)
                .build();

        UUID jobId = llmJobService.submit(req);

        assertNotNull(jobId, "submit must return a non-null UUID");

        LlmJob saved = llmJobRepository.findById(jobId)
                .orElseThrow(() -> new AssertionError("LlmJob row not found after submit"));

        assertEquals(LlmJob.Status.QUEUED, saved.getStatus(),
                "Newly submitted job must have status=QUEUED");
        assertNotNull(saved.getRequestPayload(),
                "request_payload must be populated");
        assertNull(saved.getProviderJobId(),
                "provider_job_id must be null before submitter runs");
        assertEquals("synthesis_identify", saved.getPurpose());
        assertEquals(1L, saved.getClaimId());
        assertEquals(100L, saved.getUserId());
    }

    // -----------------------------------------------------------------------
    // Scenario 2: submit_thenSubmitterRunsThenSucceeded
    // -----------------------------------------------------------------------

    @Test
    void submit_thenSubmitterRunsThenSucceeded() {
        LlmJobRequest req = LlmJobRequest.builder()
                .purpose("synthesis_identify")
                .userMessage("Identify conditions.")
                .claimId(1L)
                .userId(100L)
                .build();

        UUID jobId = llmJobService.submit(req);

        // Submitter tick transitions QUEUED → SUBMITTED with provider_job_id set.
        llmJobSubmitter.tick();

        LlmJob afterSubmit = llmJobRepository.findById(jobId).orElseThrow();
        assertEquals(LlmJob.Status.SUBMITTED, afterSubmit.getStatus(),
                "After submitter tick, status must be SUBMITTED");
        assertNotNull(afterSubmit.getProviderJobId(),
                "provider_job_id must be set after submitter tick");
        assertNotNull(afterSubmit.getSubmittedAt(),
                "submitted_at must be set after submitter tick");
        assertEquals(1, afterSubmit.getAttempts(),
                "attempts must be incremented to 1 after first submission");

        // Poller tick transitions SUBMITTED → SUCCEEDED (FakeLlmAsyncProvider returns SUCCEEDED).
        llmJobPoller.tick();

        LlmJob afterPoll = llmJobRepository.findById(jobId).orElseThrow();
        assertEquals(LlmJob.Status.SUCCEEDED, afterPoll.getStatus(),
                "After poller tick with fake returning SUCCEEDED, status must be SUCCEEDED");
        assertNotNull(afterPoll.getResponsePayload(),
                "response_payload must be populated after poller fetches result");
        assertNotNull(afterPoll.getCompletedAt(),
                "completed_at must be set when job SUCCEEDED");

        // Verify response payload contains the canned text.
        Optional<LlmJobResult> result = llmJobService.getResult(jobId);
        assertTrue(result.isPresent(), "getResult must return non-empty for SUCCEEDED job");
        assertTrue(result.get().getText().contains("PTSD"),
                "Result text must match the canned response set on FakeLlmAsyncProvider");
    }

    // -----------------------------------------------------------------------
    // Scenario 3: getResult_emptyWhileInProgress
    // -----------------------------------------------------------------------

    @Test
    void getResult_emptyWhileInProgress() {
        // Force fake to return IN_PROGRESS so the poller won't flip to SUCCEEDED.
        fakeProvider.setStatus("synthesis_identify", ProviderJobStatus.IN_PROGRESS);

        LlmJobRequest req = LlmJobRequest.builder()
                .purpose("synthesis_identify")
                .userMessage("Identify conditions.")
                .claimId(2L)
                .userId(100L)
                .build();

        UUID jobId = llmJobService.submit(req);
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        // Job should be IN_PROGRESS after poller runs with forced IN_PROGRESS status.
        LlmJob job = llmJobRepository.findById(jobId).orElseThrow();
        assertTrue(
                job.getStatus() == LlmJob.Status.SUBMITTED || job.getStatus() == LlmJob.Status.IN_PROGRESS,
                "Job must remain non-terminal while provider returns IN_PROGRESS"
        );

        Optional<LlmJobResult> result = llmJobService.getResult(jobId);
        assertTrue(result.isEmpty(),
                "getResult must return Optional.empty() while job is still in progress");
    }

    // -----------------------------------------------------------------------
    // Scenario 4: getResult_returnsResultWhenSucceeded
    // -----------------------------------------------------------------------

    @Test
    void getResult_returnsResultWhenSucceeded() {
        LlmJobRequest req = LlmJobRequest.builder()
                .purpose("synthesis_identify")
                .userMessage("Identify conditions.")
                .claimId(3L)
                .userId(100L)
                .build();

        UUID jobId = llmJobService.submit(req);
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        LlmJob job = llmJobRepository.findById(jobId).orElseThrow();
        assertEquals(LlmJob.Status.SUCCEEDED, job.getStatus());

        Optional<LlmJobResult> result = llmJobService.getResult(jobId);
        assertTrue(result.isPresent(), "getResult must return non-empty for SUCCEEDED job");
        assertNotNull(result.get().getText(), "Result text must not be null");
        assertFalse(result.get().getText().isBlank(), "Result text must not be blank");
        // Canned response for synthesis_identify contains "PTSD".
        assertTrue(result.get().getText().contains("PTSD"),
                "Result text must match the canned response");
    }

    // -----------------------------------------------------------------------
    // Scenario 5: getResult_throwsOnFailed
    // -----------------------------------------------------------------------

    @Test
    void getResult_throwsOnFailed() {
        // Manually persist a FAILED job to simulate a job that completed with an error.
        LlmJob failedJob = LlmJob.builder()
                .provider("fake-llm")
                .modelName("fake-model-1")
                .purpose("synthesis_identify")
                .status(LlmJob.Status.FAILED)
                .claimId(99L)
                .userId(100L)
                .requestPayload("{\"purpose\":\"synthesis_identify\",\"userMessage\":\"test\"}")
                .errorMessage("Provider returned HTTP 500")
                .attempts(3)
                .completedAt(Instant.now())
                .build();
        LlmJob saved = llmJobRepository.save(failedJob);

        LlmJobService.LlmJobFailedException ex = assertThrows(
                LlmJobService.LlmJobFailedException.class,
                () -> llmJobService.getResult(saved.getId()),
                "getResult must throw LlmJobFailedException for FAILED jobs"
        );
        assertEquals(saved.getId(), ex.getJobId(),
                "Exception must carry the correct job id");
        assertTrue(ex.getMessage().contains("Provider returned HTTP 500"),
                "Exception message must contain the error_message from the job row");
    }

    // -----------------------------------------------------------------------
    // Scenario 6: allComplete_trueOnlyWhenAllTerminal
    // -----------------------------------------------------------------------

    @Test
    void allSucceeded_falseWhileOneJobStillInProgress() {
        // Submit 3 jobs. Force one purpose to stay IN_PROGRESS.
        fakeProvider.setResponse("gap_evidence",
                "[{\"gap\":\"no nexus letter\",\"severity\":\"high\"}]");
        fakeProvider.setStatus("gap_whatif", ProviderJobStatus.IN_PROGRESS);
        fakeProvider.setResponse("gap_whatif", "[]");

        UUID id1 = llmJobService.submit(LlmJobRequest.builder()
                .purpose("gap_evidence").userMessage("gaps?").claimId(10L).userId(1L).build());
        UUID id2 = llmJobService.submit(LlmJobRequest.builder()
                .purpose("gap_evidence").userMessage("gaps?").claimId(10L).userId(1L).build());
        UUID id3 = llmJobService.submit(LlmJobRequest.builder()
                .purpose("gap_whatif").userMessage("whatif?").claimId(10L).userId(1L).build());

        llmJobSubmitter.tick();
        llmJobPoller.tick();

        Set<UUID> allThree = Set.of(id1, id2, id3);

        // id1 and id2 are gap_evidence → SUCCEEDED; id3 is gap_whatif → still IN_PROGRESS.
        assertFalse(llmJobService.allSucceeded(allThree),
                "allSucceeded must return false while one job is still in progress");

        // allTerminal must also be false (IN_PROGRESS is not terminal).
        assertFalse(llmJobService.allTerminal(allThree),
                "allTerminal must return false while one job is not in a terminal state");
    }

    @Test
    void allSucceeded_trueWhenAllJobsSucceeded() {
        fakeProvider.setResponse("gap_evidence",
                "[{\"gap\":\"no nexus\",\"severity\":\"high\"}]");

        UUID id1 = llmJobService.submit(LlmJobRequest.builder()
                .purpose("gap_evidence").userMessage("gaps?").claimId(11L).userId(1L).build());
        UUID id2 = llmJobService.submit(LlmJobRequest.builder()
                .purpose("gap_evidence").userMessage("gaps?").claimId(11L).userId(1L).build());
        UUID id3 = llmJobService.submit(LlmJobRequest.builder()
                .purpose("gap_evidence").userMessage("gaps?").claimId(11L).userId(1L).build());

        llmJobSubmitter.tick();
        llmJobPoller.tick();

        Set<UUID> allThree = Set.of(id1, id2, id3);

        assertTrue(llmJobService.allSucceeded(allThree),
                "allSucceeded must return true when all jobs are SUCCEEDED");
        assertTrue(llmJobService.allTerminal(allThree),
                "allTerminal must return true when all jobs are SUCCEEDED (terminal)");
    }

    @Test
    void allTerminal_trueWhenAJobIsFailed() {
        // A FAILED job is terminal even though it didn't succeed.
        LlmJob failedJob = LlmJob.builder()
                .provider("fake-llm")
                .modelName("fake-model-1")
                .purpose("synthesis_identify")
                .status(LlmJob.Status.FAILED)
                .claimId(12L)
                .userId(100L)
                .requestPayload("{\"purpose\":\"synthesis_identify\",\"userMessage\":\"x\"}")
                .errorMessage("timeout")
                .attempts(3)
                .completedAt(Instant.now())
                .build();
        LlmJob saved = llmJobRepository.save(failedJob);

        fakeProvider.setResponse("gap_evidence", "[]");
        UUID succeededId = llmJobService.submit(LlmJobRequest.builder()
                .purpose("gap_evidence").userMessage("gaps?").claimId(12L).userId(1L).build());
        llmJobSubmitter.tick();
        llmJobPoller.tick();

        Set<UUID> mixed = Set.of(saved.getId(), succeededId);

        assertTrue(llmJobService.allTerminal(mixed),
                "allTerminal must return true when all jobs are in terminal states (SUCCEEDED + FAILED)");
        assertFalse(llmJobService.allSucceeded(mixed),
                "allSucceeded must return false when one job is FAILED");
    }
}
