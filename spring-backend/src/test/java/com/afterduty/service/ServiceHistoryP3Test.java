package com.afterduty.service;

import com.afterduty.dto.ServicePeriodDto;
import com.afterduty.model.Atom;
import com.afterduty.model.ServiceHistoryOverride;
import com.afterduty.model.ServiceHistoryResolution;
import com.afterduty.service.ServiceHistoryReconciler.Conflict;
import com.afterduty.service.ServiceHistoryReconciler.RawPeriod;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service History P3 (design 2026-07-05): the STABLE cluster key, veteran override
 * at top authority, LLM-resolution layering (override &gt; resolution &gt;
 * deterministic), and genuine-conflict detection. Drives the deterministic core
 * through {@link ServicePeriodDeriver#derive} (reconcile flag ON) — no Spring, no
 * LLM, no repos.
 */
@Tag("regression")
class ServiceHistoryP3Test {

    private ServicePeriodDeriver deriver() {
        return new ServicePeriodDeriver(null, null, null, null, new ServiceHistoryReconciler());
    }

    private static Atom atom(Long evidenceId, String value) {
        return Atom.builder()
                .claimId(1L).evidenceId(evidenceId).type("service_record_detail")
                .value(value).source("record.pdf").createdBy("ai:extraction-service-record").build();
    }

    private static List<Atom> record(Long evidenceId, String branch, String start, String end,
                                     String mos, String rank) {
        List<Atom> atoms = new ArrayList<>();
        if (branch != null) atoms.add(atom(evidenceId, "Branch of Service: " + branch));
        if (rank != null) atoms.add(atom(evidenceId, "Rank: " + rank));
        if (mos != null) atoms.add(atom(evidenceId, "MOS/Rating/AFSC: " + mos));
        if (start != null || end != null) {
            StringBuilder sp = new StringBuilder("Service Period:");
            if (start != null) sp.append(" Enlistment: ").append(start);
            if (start != null && end != null) sp.append(" |");
            if (end != null) sp.append(" Separation: ").append(end);
            atoms.add(atom(evidenceId, sp.toString()));
        }
        return atoms;
    }

    private static ServiceHistoryOverride override(String clusterKey) {
        ServiceHistoryOverride o = new ServiceHistoryOverride();
        o.setClusterKey(clusterKey);
        return o;
    }

    private static ServiceHistoryResolution resolution(String clusterKey, String field, String value) {
        ServiceHistoryResolution r = new ServiceHistoryResolution();
        r.setClusterKey(clusterKey);
        r.setResolvedField(field);
        r.setResolvedValue(value);
        r.setReasoning("more consistent with a continuous term");
        return r;
    }

    // -------------------------------------------------------------------------
    // Stable cluster key
    // -------------------------------------------------------------------------

    @Test
    void clusterKey_isPopulatedOnReconciledConclusions() {
        List<Atom> atoms = new ArrayList<>(record(1L, "US Navy", "2001-06-01", "2009-06-01", "IT", "PO1"));
        List<ServicePeriodDto> out = deriver().derive(atoms, null, Map.of(1L, "DD-214"));
        assertThat(out).hasSize(1);
        // canonicalBranch|component|earliestStartYear
        assertThat(out.get(0).getClusterKey()).isEqualTo("Navy|active|2001");
    }

    @Test
    void clusterKey_stableAcrossReDerive_forTheSameEnlistment() {
        // First derive: one DD-214.
        List<Atom> gen1 = new ArrayList<>(record(10L, "NAVY USN", "2001-06-01", "2009-06-01", "9213", "CTI1"));
        String key1 = deriver().derive(gen1, null, Map.of(10L, "DD-214")).get(0).getClusterKey();

        // Re-derive after a NEW upload of the SAME enlistment (different evidenceId,
        // different branch wording, a jittered end date, extra MOS) — the real
        // enlistment is unchanged, so the key must NOT move.
        List<Atom> gen2 = new ArrayList<>();
        gen2.addAll(record(10L, "NAVY USN", "2001-06-01", "2009-06-01", "9213", "CTI1"));
        gen2.addAll(record(11L, "US Navy", "2001-06-01", "2008-06-01", "063", "PO1"));
        gen2.addAll(record(12L, "Navy", null, null, null, null));
        Map<Long, String> classes = new HashMap<>();
        classes.put(10L, "DD-214");
        classes.put(11L, "Orders");
        classes.put(12L, "Military Record");
        List<ServicePeriodDto> out2 = deriver().derive(gen2, null, classes);

        assertThat(out2).hasSize(1);
        assertThat(out2.get(0).getClusterKey()).isEqualTo(key1);
    }

    @Test
    void clusterKey_differsAcrossDifferentEnlistments() {
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(20L, "US Army", "1990-01-01", "1994-01-01", "11B", "SGT"));
        atoms.addAll(record(21L, "US Navy", "2001-01-01", "2009-01-01", "IT", "PO1"));
        Map<Long, String> classes = new HashMap<>();
        classes.put(20L, "DD-214");
        classes.put(21L, "DD-214");

        List<ServicePeriodDto> out = deriver().derive(atoms, null, classes);
        assertThat(out).hasSize(2);
        assertThat(out).extracting(ServicePeriodDto::getClusterKey)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrder("Army|active|1990", "Navy|active|2001");
    }

    @Test
    void clusterKey_undatedOnlyCluster_hashesDeterministically_andIsStable() {
        // A branch-only, dateless cluster keys on a hash of its evidenceIds.
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(30L, "Army", null, null, null, null));
        atoms.addAll(record(31L, "US Army", null, null, null, null));

        String a = deriver().derive(atoms, null, Map.of()).get(0).getClusterKey();
        String b = deriver().derive(atoms, null, Map.of()).get(0).getClusterKey();
        assertThat(a).isEqualTo(b);              // deterministic
        assertThat(a).startsWith("Army|active|h:"); // hash fallback, not a year
    }

    // -------------------------------------------------------------------------
    // Veteran override at TOP authority
    // -------------------------------------------------------------------------

    @Test
    void override_appliesAtTopAuthority_andTagsCorrectedByYou() {
        List<Atom> atoms = new ArrayList<>(record(40L, "US Navy", "2001-06-01", "2009-06-01", "IT", "PO1"));
        Map<Long, String> classes = Map.of(40L, "DD-214");
        String key = deriver().derive(atoms, null, classes).get(0).getClusterKey();

        // Veteran corrects the end date (the DD-214 is authoritative, but the vet
        // knows better) — override wins over the document.
        ServiceHistoryOverride ov = override(key);
        ov.setEndDate("2010-06-01");
        List<ServicePeriodDto> out = deriver().derive(atoms, null, classes, Map.of(key, ov), Map.of());

        assertThat(out).hasSize(1);
        ServicePeriodDto c = out.get(0);
        assertThat(c.getEndDate()).isEqualTo("2010-06-01");    // override beats DD-214
        assertThat(c.getStartDate()).isEqualTo("2001-06-01");  // untouched field kept
        assertThat(c.getTotalYears()).isEqualTo(9);            // recomputed 2010-2001
        assertThat(c.getReasoning()).contains("Corrected by you.");
    }

    @Test
    void override_onlySetFieldsWin_nullFieldsLeaveReconciledValue() {
        List<Atom> atoms = new ArrayList<>(record(50L, "Navy", "2001-06-01", "2009-06-01", "IT", "PO1"));
        Map<Long, String> classes = Map.of(50L, "DD-214");
        String key = deriver().derive(atoms, null, classes).get(0).getClusterKey();

        ServiceHistoryOverride ov = override(key); // only branch set
        ov.setBranch("Coast Guard");
        List<ServicePeriodDto> out = deriver().derive(atoms, null, classes, Map.of(key, ov), Map.of());

        ServicePeriodDto c = out.get(0);
        assertThat(c.getBranch()).isEqualTo("Coast Guard");
        assertThat(c.getRank()).isEqualTo("PO1");          // untouched
        assertThat(c.getEndDate()).isEqualTo("2009-06-01"); // untouched
    }

    @Test
    void override_reAttachesAfterReDerive_viaStableKey() {
        // Persist an override against the key derived from gen1, then re-derive with
        // a new upload — the override must still land on the same conclusion.
        List<Atom> gen1 = new ArrayList<>(record(60L, "NAVY USN", "2001-06-01", "2009-06-01", "IT", "CTI1"));
        String key = deriver().derive(gen1, null, Map.of(60L, "DD-214")).get(0).getClusterKey();
        ServiceHistoryOverride ov = override(key);
        ov.setRank("CPO");

        List<Atom> gen2 = new ArrayList<>();
        gen2.addAll(record(60L, "NAVY USN", "2001-06-01", "2009-06-01", "IT", "CTI1"));
        gen2.addAll(record(61L, "US Navy", null, "2008-06-01", null, null));
        Map<Long, String> classes = new HashMap<>();
        classes.put(60L, "DD-214");
        classes.put(61L, "Orders");

        List<ServicePeriodDto> out = deriver().derive(gen2, null, classes, Map.of(key, ov), Map.of());
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getRank()).isEqualTo("CPO");                // override re-attached
        assertThat(out.get(0).getReasoning()).contains("Corrected by you.");
    }

    // -------------------------------------------------------------------------
    // LLM-resolution layering — below override, above deterministic
    // -------------------------------------------------------------------------

    @Test
    void resolution_appliedAboveDeterministic_whenNoOverride() {
        // Two EQUAL-authority DD-214s disagree on the end date. Deterministic pick
        // is the widest (2009); a persisted resolution says 2008 — resolution wins.
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(70L, "Navy", "2001-06-01", "2009-06-01", "IT", "PO1"));
        atoms.addAll(record(71L, "Navy", "2001-06-01", "2008-06-01", "IT", "PO1"));
        Map<Long, String> classes = new HashMap<>();
        classes.put(70L, "DD-214");
        classes.put(71L, "DD-214");
        String key = deriver().derive(atoms, null, classes).get(0).getClusterKey();

        ServiceHistoryResolution res = resolution(key, ServiceHistoryResolution.FIELD_END, "2008-06-01");
        List<ServicePeriodDto> out = deriver().derive(atoms, null, classes, Map.of(), Map.of(key, res));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getEndDate()).isEqualTo("2008-06-01");
        assertThat(out.get(0).getReasoning()).contains("Adjudicated");
    }

    @Test
    void override_beatsResolution_whenBothPresent() {
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(80L, "Navy", "2001-06-01", "2009-06-01", "IT", "PO1"));
        atoms.addAll(record(81L, "Navy", "2001-06-01", "2008-06-01", "IT", "PO1"));
        Map<Long, String> classes = new HashMap<>();
        classes.put(80L, "DD-214");
        classes.put(81L, "DD-214");
        String key = deriver().derive(atoms, null, classes).get(0).getClusterKey();

        ServiceHistoryResolution res = resolution(key, ServiceHistoryResolution.FIELD_END, "2008-06-01");
        ServiceHistoryOverride ov = override(key);
        ov.setEndDate("2011-06-01"); // the veteran overrides even the LLM verdict

        List<ServicePeriodDto> out =
                deriver().derive(atoms, null, classes, Map.of(key, ov), Map.of(key, res));
        assertThat(out.get(0).getEndDate()).isEqualTo("2011-06-01"); // override on top
        assertThat(out.get(0).getReasoning()).contains("Corrected by you.");
    }

    // -------------------------------------------------------------------------
    // Conflict detection — fires ONLY on equal-authority contradictions
    // -------------------------------------------------------------------------

    @Test
    void conflictDetection_firesOnEqualAuthorityContradiction() {
        ServiceHistoryReconciler reconciler = new ServiceHistoryReconciler();
        // Two DD-214s (equal authority) disagree on the end date → genuine conflict.
        List<RawPeriod> raw = new ArrayList<>();
        raw.add(new RawPeriod(90L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2009-06-01", "IT", "PO1", "documents")));
        raw.add(new RawPeriod(91L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2008-06-01", "IT", "PO1", "documents")));
        Map<Long, String> classes = new HashMap<>();
        classes.put(90L, "DD-214");
        classes.put(91L, "DD-214");

        List<Conflict> conflicts = reconciler.detectConflicts(raw, classes);
        assertThat(conflicts).hasSize(1);
        Conflict c = conflicts.get(0);
        assertThat(c.field()).isEqualTo("end");
        assertThat(List.of(c.valueA(), c.valueB())).containsExactlyInAnyOrder("2009-06-01", "2008-06-01");
        assertThat(c.evidenceFingerprint()).isNotBlank();
    }

    @Test
    void conflictDetection_doesNotFire_whenAuthorityResolvesIt() {
        ServiceHistoryReconciler reconciler = new ServiceHistoryReconciler();
        // DD-214 (100) vs Orders (50) disagree — authority resolves it, NOT a conflict.
        List<RawPeriod> raw = new ArrayList<>();
        raw.add(new RawPeriod(100L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2009-06-01", "IT", "PO1", "documents")));
        raw.add(new RawPeriod(101L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2008-06-01", "IT", "PO1", "documents")));
        Map<Long, String> classes = new HashMap<>();
        classes.put(100L, "DD-214");
        classes.put(101L, "Deployment Orders");

        assertThat(reconciler.detectConflicts(raw, classes)).isEmpty();
    }

    @Test
    void conflictDetection_doesNotFire_whenSourcesAgree() {
        ServiceHistoryReconciler reconciler = new ServiceHistoryReconciler();
        List<RawPeriod> raw = new ArrayList<>();
        raw.add(new RawPeriod(110L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2009-06-01", "IT", "PO1", "documents")));
        raw.add(new RawPeriod(111L, new ServicePeriodDto("Navy", "active",
                "2001-06-01", "2009-06-01", "IT", "PO1", "documents")));
        Map<Long, String> classes = new HashMap<>();
        classes.put(110L, "DD-214");
        classes.put(111L, "DD-214");

        assertThat(reconciler.detectConflicts(raw, classes)).isEmpty();
    }
}
