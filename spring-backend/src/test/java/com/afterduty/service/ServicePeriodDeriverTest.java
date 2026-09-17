package com.afterduty.service;

import com.afterduty.dto.ServicePeriodDto;
import com.afterduty.model.Atom;
import com.afterduty.model.ServiceProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ServicePeriodDeriver.derive — the pure, deterministic core that turns
 * already-extracted service_record_detail atoms (labeled string values, one
 * group per evidence/document) + the optional manual ServiceProfile row into
 * the servicePeriods[] wire shape. No Spring, no LLM, no repos.
 *
 * <p>These cases pin the LEGACY exact-dedupe contract (reconcile flag OFF) — the
 * fallback path that {@code SERVICE_HISTORY_RECONCILE=false} preserves byte-for-byte.
 * Reconciliation behavior (flag ON, default) lives in {@link ServiceHistoryReconcilerTest}.
 */
@Tag("regression")
class ServicePeriodDeriverTest {

    private final ServicePeriodDeriver deriver = legacyDeriver();

    /** Deriver with the reconcile flag forced OFF — exercises the legacy path. */
    private static ServicePeriodDeriver legacyDeriver() {
        ServicePeriodDeriver d = new ServicePeriodDeriver(null, null, null, null,
                new ServiceHistoryReconciler());
        d.setReconcileEnabled(false);
        return d;
    }

    // -------------------------------------------------------------------------
    // Helpers — atoms exactly as ServiceRecordExtractorService emits and
    // ExtractionStateMachine.saveAtoms persists them.
    // -------------------------------------------------------------------------

    private static Atom atom(Long evidenceId, String value) {
        return Atom.builder()
                .claimId(1L)
                .evidenceId(evidenceId)
                .type("service_record_detail")
                .value(value)
                .source("dd214.pdf")
                .createdBy("ai:extraction-service-record")
                .build();
    }

    private static List<Atom> dd214(Long evidenceId, String branch, String start, String end,
                                    String mos, String rank) {
        List<Atom> atoms = new ArrayList<>();
        atoms.add(atom(evidenceId, "Branch of Service: " + branch));
        atoms.add(atom(evidenceId, "Rank: " + rank));
        atoms.add(atom(evidenceId, "MOS/Rating/AFSC: " + mos));
        atoms.add(atom(evidenceId, "Discharge Type: honorable"));
        atoms.add(atom(evidenceId,
                "Service Period: Enlistment: " + start + " | Separation: " + end + " | Total Years: 4"));
        return atoms;
    }

    // -------------------------------------------------------------------------
    // Single DD-214 → one fully-mapped period
    // -------------------------------------------------------------------------

    @Test
    void singleDd214_yieldsOneDocumentPeriod() {
        List<ServicePeriodDto> periods = deriver.derive(
                dd214(10L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT"), null);

        assertThat(periods).hasSize(1);
        ServicePeriodDto p = periods.get(0);
        assertThat(p.getBranch()).isEqualTo("Army");
        assertThat(p.getComponent()).isEqualTo("active");
        assertThat(p.getStartDate()).isEqualTo("2001-05-14");
        assertThat(p.getEndDate()).isEqualTo("2005-08-30");
        assertThat(p.getMos()).isEqualTo("11B");
        assertThat(p.getRank()).isEqualTo("SGT");
        assertThat(p.getSource()).isEqualTo("documents");
    }

    // -------------------------------------------------------------------------
    // Two documents including a Guard record → two periods, newest-first
    // -------------------------------------------------------------------------

    @Test
    void twoDocuments_activeThenGuard_yieldsTwoPeriodsNewestFirst() {
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(dd214(10L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT"));
        atoms.addAll(dd214(11L, "Army National Guard", "2006-01-10", "2012-03-01", "11B", "SSG"));

        List<ServicePeriodDto> periods = deriver.derive(atoms, null);

        assertThat(periods).hasSize(2);
        // Newest-first: the Guard period (2006) sorts before the active one (2001).
        assertThat(periods.get(0).getBranch()).isEqualTo("Army National Guard");
        assertThat(periods.get(0).getComponent()).isEqualTo("guard");
        assertThat(periods.get(1).getBranch()).isEqualTo("Army");
        assertThat(periods.get(1).getComponent()).isEqualTo("active");
    }

    // -------------------------------------------------------------------------
    // Re-upload dedupe — same branch + same dates collapses to one period
    // -------------------------------------------------------------------------

    @Test
    void reupload_sameBranchAndDates_dedupesToOnePeriod() {
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(dd214(10L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT"));
        atoms.addAll(dd214(20L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT")); // re-upload

        assertThat(deriver.derive(atoms, null)).hasSize(1);
    }

    @Test
    void dedupe_isCaseInsensitiveOnBranch() {
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(dd214(10L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT"));
        atoms.addAll(dd214(20L, "ARMY", "2001-05-14", "2005-08-30", "11B", "SGT"));

        assertThat(deriver.derive(atoms, null)).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Component detection rules (deterministic string rules)
    // -------------------------------------------------------------------------

    @Test
    void componentRules_branchAndDischargeText() {
        assertThat(ServicePeriodDeriver.detectComponent("Army National Guard", null)).isEqualTo("guard");
        assertThat(ServicePeriodDeriver.detectComponent("Air National Guard", null)).isEqualTo("guard");
        assertThat(ServicePeriodDeriver.detectComponent("Navy Reserve", null)).isEqualTo("reserve");
        assertThat(ServicePeriodDeriver.detectComponent("Army", "Character of Service: Transferred to Army Reserve"))
                .isEqualTo("reserve");
        // "national guard" wins over "reserve" (Guard paperwork mentions Reserve boilerplate).
        assertThat(ServicePeriodDeriver.detectComponent("Army National Guard", "Reserve component"))
                .isEqualTo("guard");
        assertThat(ServicePeriodDeriver.detectComponent("Army", null)).isEqualTo("active");
        assertThat(ServicePeriodDeriver.detectComponent(null, "honorable")).isNull();
        assertThat(ServicePeriodDeriver.detectComponent(null, null)).isNull();
    }

    @Test
    void periodWithDatesButNoBranch_hasNullComponent() {
        List<Atom> atoms = List.of(
                atom(10L, "Service Period: Enlistment: 2001-05-14 | Separation: 2005-08-30"));

        List<ServicePeriodDto> periods = deriver.derive(atoms, null);

        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).getBranch()).isNull();
        assertThat(periods.get(0).getComponent()).isNull();
    }

    // -------------------------------------------------------------------------
    // Ordering — null start sorts last
    // -------------------------------------------------------------------------

    @Test
    void ordering_newestFirst_nullStartSortsLast() {
        List<Atom> atoms = new ArrayList<>();
        // Document with branch only — no dates at all.
        atoms.add(atom(30L, "Branch of Service: Coast Guard"));
        atoms.addAll(dd214(10L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT"));
        atoms.addAll(dd214(11L, "Navy", "2010-02-01", "2014-06-30", "HM", "PO2"));

        List<ServicePeriodDto> periods = deriver.derive(atoms, null);

        assertThat(periods).extracting(ServicePeriodDto::getBranch)
                .containsExactly("Navy", "Army", "Coast Guard");
        assertThat(periods.get(2).getStartDate()).isNull();
    }

    // -------------------------------------------------------------------------
    // Manual ServiceProfile row merge + dedupe against derived periods
    // -------------------------------------------------------------------------

    @Test
    void manualRow_appearsAsManualPeriodAlongsideDocumentPeriods() {
        ServiceProfile manual = ServiceProfile.builder()
                .branch("Marine Corps Reserve")
                .serviceStart("2015-07-01")
                .serviceEnd("2019-06-30")
                .mos("0311")
                .build();

        List<ServicePeriodDto> periods = deriver.derive(
                dd214(10L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT"), manual);

        assertThat(periods).hasSize(2);
        ServicePeriodDto manualPeriod = periods.get(0); // 2015 start → newest first
        assertThat(manualPeriod.getSource()).isEqualTo("manual");
        assertThat(manualPeriod.getBranch()).isEqualTo("Marine Corps Reserve");
        assertThat(manualPeriod.getComponent()).isEqualTo("reserve");
        assertThat(manualPeriod.getMos()).isEqualTo("0311");
        assertThat(manualPeriod.getRank()).isNull();
        assertThat(periods.get(1).getSource()).isEqualTo("documents");
    }

    @Test
    void manualRow_matchingDerivedBranchAndDates_isDeduped_documentPeriodWins() {
        ServiceProfile manual = ServiceProfile.builder()
                .branch("Army")
                .serviceStart("2001-05-14")
                .serviceEnd("2005-08-30")
                .mos("11B")
                .build();

        List<ServicePeriodDto> periods = deriver.derive(
                dd214(10L, "Army", "2001-05-14", "2005-08-30", "11B", "SGT"), manual);

        assertThat(periods).hasSize(1);
        // The document-derived period wins — it is richer (carries rank).
        assertThat(periods.get(0).getSource()).isEqualTo("documents");
        assertThat(periods.get(0).getRank()).isEqualTo("SGT");
    }

    @Test
    void manualRow_nonIsoDates_areNulledNotInvented() {
        ServiceProfile manual = ServiceProfile.builder()
                .branch("Army")
                .serviceStart("June 2001")
                .serviceEnd("08/30/2005")
                .build();

        List<ServicePeriodDto> periods = deriver.derive(List.of(), manual);

        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).getStartDate()).isNull();
        assertThat(periods.get(0).getEndDate()).isNull();
        assertThat(periods.get(0).getBranch()).isEqualTo("Army");
    }

    // -------------------------------------------------------------------------
    // Nothing derivable → empty list (never null); noise atoms ignored
    // -------------------------------------------------------------------------

    @Test
    void noAtomsNoManualRow_yieldsEmptyList() {
        assertThat(deriver.derive(List.of(), null)).isEmpty();
        assertThat(deriver.derive(null, null)).isEmpty();
    }

    @Test
    void nonServiceAtomsAndRankOnlyGroups_produceNoPeriods() {
        List<Atom> atoms = List.of(
                Atom.builder().claimId(1L).evidenceId(10L).type("diagnosis")
                        .value("Branch of Service: Army").source("notes.pdf").build(),
                // A document whose only service atoms are rank/mos — not period-shaped.
                atom(11L, "Rank: SGT"),
                atom(11L, "MOS/Rating/AFSC: 11B"));

        assertThat(deriver.derive(atoms, null)).isEmpty();
    }
}
