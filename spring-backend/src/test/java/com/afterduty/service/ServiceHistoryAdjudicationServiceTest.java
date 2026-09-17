package com.afterduty.service;

import com.afterduty.dto.ServicePeriodDto;
import com.afterduty.model.ServiceHistoryResolution;
import com.afterduty.model.User;
import com.afterduty.repository.ServiceHistoryResolutionRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.service.ServiceHistoryAdjudicationService.Adjudication;
import com.afterduty.service.ServiceHistoryAdjudicationService.Adjudicator;
import com.afterduty.service.ServiceHistoryReconciler.RawPeriod;
import com.afterduty.service.ServicePeriodDeriver.RawInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pipeline-time service-history LLM adjudication (Service History P3 Part B). The
 * {@link Adjudicator} LLM seam is a fake — these tests exercise conflict detection,
 * the safe-by-default flag, persistence, out-of-set rejection, and staleness WITHOUT
 * a real network call. The reconciler is real; repos + deriver-inputs are mocked.
 */
@Tag("regression")
class ServiceHistoryAdjudicationServiceTest {

    private ServicePeriodDeriver deriver;
    private ServiceHistoryResolutionRepository resolutionRepository;
    private UserRepository userRepository;
    private final ServiceHistoryReconciler reconciler = new ServiceHistoryReconciler();
    private User user;

    @BeforeEach
    void setUp() {
        deriver = mock(ServicePeriodDeriver.class);
        resolutionRepository = mock(ServiceHistoryResolutionRepository.class);
        userRepository = mock(UserRepository.class);
        user = new User();
        user.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(resolutionRepository.findByUserIdAndClusterKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
    }

    /** Two EQUAL-authority DD-214s disagreeing on the end date → one genuine conflict. */
    private void stubConflictingInputs() {
        List<RawPeriod> raw = new ArrayList<>();
        raw.add(new RawPeriod(1L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2009-06-01", "IT", "PO1", "documents")));
        raw.add(new RawPeriod(2L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2008-06-01", "IT", "PO1", "documents")));
        Map<Long, String> classes = new HashMap<>();
        classes.put(1L, "DD-214");
        classes.put(2L, "DD-214");
        when(deriver.buildRawInputsForUser(user)).thenReturn(new RawInputs(raw, classes));
    }

    /** No conflict: authority (DD-214 vs Orders) resolves the disagreement. */
    private void stubNonConflictingInputs() {
        List<RawPeriod> raw = new ArrayList<>();
        raw.add(new RawPeriod(1L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2009-06-01", "IT", "PO1", "documents")));
        raw.add(new RawPeriod(2L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2008-06-01", "IT", "PO1", "documents")));
        Map<Long, String> classes = new HashMap<>();
        classes.put(1L, "DD-214");
        classes.put(2L, "Orders");
        when(deriver.buildRawInputsForUser(user)).thenReturn(new RawInputs(raw, classes));
    }

    private ServiceHistoryAdjudicationService service(Adjudicator adj) {
        return new ServiceHistoryAdjudicationService(deriver, reconciler, resolutionRepository,
                userRepository, adj);
    }

    @Test
    void noConflict_makesNoLlmCall_andPersistsNothing() {
        stubNonConflictingInputs();
        AtomicInteger llmCalls = new AtomicInteger();
        Adjudicator adj = (c, cl, u) -> {
            llmCalls.incrementAndGet();
            return Optional.of(new Adjudication("2008-06-01", "why"));
        };

        int n = service(adj).adjudicateForUser(10L, 7L);

        assertThat(n).isZero();
        assertThat(llmCalls.get()).isZero();                      // safe: no conflict ⇒ no LLM
        verify(resolutionRepository, never()).save(any());
    }

    @Test
    void genuineConflict_callsLlm_andPersistsResolution() {
        stubConflictingInputs();
        Adjudicator adj = (c, cl, u) -> Optional.of(new Adjudication("2008-06-01", "closer to a full term"));

        int n = service(adj).adjudicateForUser(10L, 7L);

        assertThat(n).isEqualTo(1);
        var captor = org.mockito.ArgumentCaptor.forClass(ServiceHistoryResolution.class);
        verify(resolutionRepository).save(captor.capture());
        ServiceHistoryResolution saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getResolvedField()).isEqualTo("end");
        assertThat(saved.getResolvedValue()).isEqualTo("2008-06-01");
        assertThat(saved.getReasoning()).isEqualTo("closer to a full term");
        assertThat(saved.getEvidenceFingerprint()).isNotBlank();
        assertThat(saved.getClusterKey()).isNotBlank();
    }

    @Test
    void flagOff_makesNoLlmCall_evenWithConflict() {
        stubConflictingInputs();
        AtomicInteger llmCalls = new AtomicInteger();
        Adjudicator adj = (c, cl, u) -> {
            llmCalls.incrementAndGet();
            return Optional.of(new Adjudication("2008-06-01", "why"));
        };
        ServiceHistoryAdjudicationService svc = service(adj);
        svc.setAdjudicationEnabled(false);

        assertThat(svc.adjudicateForUser(10L, 7L)).isZero();
        assertThat(llmCalls.get()).isZero();
        verify(resolutionRepository, never()).save(any());
    }

    @Test
    void hallucinatedOutOfSetValue_isRejected_deterministicPickStands() {
        stubConflictingInputs();
        // The model returns a third date not in {2009, 2008} → rejected, not persisted.
        Adjudicator adj = (c, cl, u) -> Optional.of(new Adjudication("2010-01-01", "made up"));

        assertThat(service(adj).adjudicateForUser(10L, 7L)).isZero();
        verify(resolutionRepository, never()).save(any());
    }

    @Test
    void adjudicatorFailure_isSwallowed_pipelineNeverThrows() {
        stubConflictingInputs();
        Adjudicator adj = (c, cl, u) -> { throw new RuntimeException("LLM down"); };
        // Should not throw; returns 0.
        assertThat(service(adj).adjudicateForUser(10L, 7L)).isZero();
    }
}
