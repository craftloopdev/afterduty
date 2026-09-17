package com.afterduty.service;

import com.afterduty.dto.ServicePeriodDto;
import com.afterduty.dto.ServiceSourceDto;
import com.afterduty.model.Atom;
import com.afterduty.model.ServiceProfile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service History reconciliation (design 2026-07-05). Drives the deterministic
 * {@link ServiceHistoryReconciler} through {@link ServicePeriodDeriver#derive}
 * (the real integration surface, reconcile flag ON) plus a few direct unit tests
 * of the normalization + authority helpers. No Spring, no LLM, no repos.
 *
 * <p>The corpus mirrors the production screenshot: ONE 2001–2009 Navy enlistment
 * that surfaced as SIX rows and inflated the profile to "~24 years total".
 */
@Tag("regression")
class ServiceHistoryReconcilerTest {

    // Deriver with reconciliation ON (default). Repos are null — derive(atoms,
    // manual, classifications) never touches them; the reconciler is injected.
    private ServicePeriodDeriver reconcilingDeriver() {
        return new ServicePeriodDeriver(null, null, null, null, new ServiceHistoryReconciler());
    }

    // -------------------------------------------------------------------------
    // Atom helpers — exactly as ServiceRecordExtractorService emits them.
    // -------------------------------------------------------------------------

    private static Atom atom(Long evidenceId, String value) {
        return Atom.builder()
                .claimId(1L)
                .evidenceId(evidenceId)
                .type("service_record_detail")
                .value(value)
                .source("record.pdf")
                .createdBy("ai:extraction-service-record")
                .build();
    }

    /** A document's service atoms; nulls skip that labeled line (missing field). */
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

    // -------------------------------------------------------------------------
    // The SIX-duplicate corpus → ONE conclusion, correct total (~8 not ~24)
    // -------------------------------------------------------------------------

    @Test
    void sixDuplicateNavyRows_collapseToOneConclusion_withCorrectTotal() {
        List<Atom> atoms = new ArrayList<>();
        // 1. "NAVY USN" 2001–2009, MOS 9213/9211, rate CTI1
        atoms.addAll(record(101L, "NAVY USN", "2001-06-01", "2009-06-01", "9213/9211", "CTI1"));
        // 2. "Navy" 2001–2009, MOS 063/221/232, rank PO1
        atoms.addAll(record(102L, "Navy", "2001-06-01", "2009-06-01", "063/221/232", "PO1"));
        // 3. "Navy" 2001–2009, no MOS/rank
        atoms.addAll(record(103L, "Navy", "2001-06-01", "2009-06-01", null, null));
        // 4. "US Navy" end 2008, no start
        atoms.addAll(record(104L, "US Navy", null, "2008-06-01", null, null));
        // 5. "Navy" no dates
        atoms.addAll(record(105L, "Navy", null, null, null, null));
        // 6. "US Navy" no dates
        atoms.addAll(record(106L, "US Navy", null, null, null, null));

        // Doc types: DD-214 is the authoritative separation record (row 1);
        // the 2008 end (row 4) comes from a lower-authority orders record.
        Map<Long, String> classes = new HashMap<>();
        classes.put(101L, "DD-214");
        classes.put(102L, "Service Personnel Record");
        classes.put(103L, "Service Personnel Record");
        classes.put(104L, "Orders");
        classes.put(105L, "Military Record");
        classes.put(106L, "Military Record");

        List<ServicePeriodDto> out = reconcilingDeriver().derive(atoms, null, classes);

        assertThat(out).hasSize(1);
        ServicePeriodDto c = out.get(0);
        assertThat(c.getBranch()).isEqualTo("Navy");                 // canonicalized
        assertThat(c.getStartDate()).isEqualTo("2001-06-01");        // earliest confident start
        assertThat(c.getEndDate()).isEqualTo("2009-06-01");          // DD-214 end beats orders 2008
        assertThat(c.getRank()).isEqualTo("CTI1");                   // from DD-214 (top authority)
        // MOS union across sources — every code present, de-duplicated.
        assertThat(c.getMos()).contains("9213", "9211", "063", "221", "232");
        assertThat(c.getTotalYears()).isEqualTo(8);                  // 2009 − 2001, NOT 24
        assertThat(c.getSources()).hasSize(6);
        assertThat(c.getReasoning()).contains("Merged 6 records");

        // Total across conclusions is this single span — the "24 years" bug is gone.
        int total = out.stream().mapToInt(p -> p.getTotalYears() == null ? 0 : p.getTotalYears()).sum();
        assertThat(total).isEqualTo(8);
    }

    // -------------------------------------------------------------------------
    // Two genuinely different enlistments stay separate → total ≈ 12
    // -------------------------------------------------------------------------

    @Test
    void twoSeparateEnlistments_stayTwoConclusions_totalIsSumOfSpans() {
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(201L, "US Army", "1990-01-01", "1994-01-01", "11B", "SGT")); // 4 yr
        atoms.addAll(record(202L, "US Navy", "2001-01-01", "2009-01-01", "IT", "PO1"));  // 8 yr

        Map<Long, String> classes = new HashMap<>();
        classes.put(201L, "DD-214");
        classes.put(202L, "DD-214");

        List<ServicePeriodDto> out = reconcilingDeriver().derive(atoms, null, classes);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(ServicePeriodDto::getBranch)
                .containsExactlyInAnyOrder("Army", "Navy");
        int total = out.stream().mapToInt(p -> p.getTotalYears() == null ? 0 : p.getTotalYears()).sum();
        assertThat(total).isEqualTo(12);
    }

    @Test
    void sameBranchButYearsApart_doNotMerge() {
        // Two Army hitches with a 6-year gap — separate enlistments, must NOT merge.
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(301L, "Army", "1985-01-01", "1989-01-01", "11B", "SGT"));
        atoms.addAll(record(302L, "Army", "1995-01-01", "1999-01-01", "11B", "SSG"));

        List<ServicePeriodDto> out = reconcilingDeriver().derive(atoms, null, Map.of());
        assertThat(out).hasSize(2);
    }

    @Test
    void differentComponents_neverMerge_evenWhenDatesOverlap() {
        // Active Army and Army National Guard overlapping in time are DIFFERENT
        // components → two conclusions (conservative).
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(401L, "Army", "2001-01-01", "2005-01-01", "11B", "SGT"));
        atoms.addAll(record(402L, "Army National Guard", "2003-01-01", "2007-01-01", "11B", "SSG"));

        List<ServicePeriodDto> out = reconcilingDeriver().derive(atoms, null, Map.of());
        assertThat(out).hasSize(2);
        assertThat(out).extracting(ServicePeriodDto::getComponent)
                .containsExactlyInAnyOrder("active", "guard");
    }

    // -------------------------------------------------------------------------
    // Doc-type precedence: 2008 (orders) vs 2009 (DD-214) → conclusion takes 2009
    // -------------------------------------------------------------------------

    @Test
    void conflictingEndDates_dd214BeatsOrders_andReasoningExplainsIt() {
        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(501L, "Navy", "2001-06-01", "2009-06-01", "IT", "PO1")); // DD-214
        atoms.addAll(record(502L, "Navy", "2001-06-01", "2008-06-01", "IT", "PO1")); // Orders

        Map<Long, String> classes = new HashMap<>();
        classes.put(501L, "DD-214 Certificate of Release or Discharge");
        classes.put(502L, "Separation Orders");

        List<ServicePeriodDto> out = reconcilingDeriver().derive(atoms, null, classes);

        assertThat(out).hasSize(1);
        ServicePeriodDto c = out.get(0);
        assertThat(c.getEndDate()).isEqualTo("2009-06-01");
        assertThat(c.getReasoning()).contains("2009-06-01");
        assertThat(c.getReasoning()).contains("chosen over");
        assertThat(c.getReasoning()).contains("2008-06-01");
    }

    // -------------------------------------------------------------------------
    // Manual/self-statement is lowest authority
    // -------------------------------------------------------------------------

    @Test
    void manualRow_mergesButNeverOutranksADocument() {
        // Manual row claims end 2007; DD-214 says 2009. DD-214 wins.
        ServiceProfile manual = ServiceProfile.builder()
                .branch("Navy")
                .serviceStart("2001-06-01")
                .serviceEnd("2007-06-01")
                .mos("IT")
                .build();
        List<Atom> atoms = new ArrayList<>(record(601L, "US Navy", "2001-06-01", "2009-06-01", "IT", "PO1"));
        Map<Long, String> classes = Map.of(601L, "DD-214");

        List<ServicePeriodDto> out = reconcilingDeriver().derive(atoms, manual, classes);

        assertThat(out).hasSize(1);
        ServicePeriodDto c = out.get(0);
        assertThat(c.getEndDate()).isEqualTo("2009-06-01");   // document beats self-statement
        // The manual row is still a recorded source (a receipt).
        assertThat(c.getSources()).anyMatch(s -> "manual".equals(s.getDocType()));
    }

    // -------------------------------------------------------------------------
    // Flag OFF → legacy exact-dedupe (no merge of the six rows)
    // -------------------------------------------------------------------------

    @Test
    void flagOff_fallsBackToExactDedupe_doesNotMergeVariants() {
        ServicePeriodDeriver deriver =
                new ServicePeriodDeriver(null, null, null, null, new ServiceHistoryReconciler());
        deriver.setReconcileEnabled(false);

        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(701L, "NAVY USN", "2001-06-01", "2009-06-01", "9213/9211", "CTI1"));
        atoms.addAll(record(702L, "Navy", "2001-06-01", "2009-06-01", "063/221/232", "PO1"));
        atoms.addAll(record(703L, "US Navy", null, "2008-06-01", null, null));

        List<ServicePeriodDto> out = deriver.derive(atoms, null, Map.of());

        // Different branch strings / dates ⇒ exact dedupe keeps them ALL apart.
        assertThat(out).hasSize(3);
        // Legacy path never populates the reconciliation fields.
        assertThat(out).allSatisfy(p -> {
            assertThat(p.getSources()).isNull();
            assertThat(p.getReasoning()).isNull();
            assertThat(p.getTotalYears()).isNull();
        });
    }

    @Test
    void flagOff_stillDedupesTrueReuploads() {
        ServicePeriodDeriver deriver =
                new ServicePeriodDeriver(null, null, null, null, new ServiceHistoryReconciler());
        deriver.setReconcileEnabled(false);

        List<Atom> atoms = new ArrayList<>();
        atoms.addAll(record(801L, "Navy", "2001-06-01", "2009-06-01", "IT", "PO1"));
        atoms.addAll(record(802L, "NAVY", "2001-06-01", "2009-06-01", "IT", "PO1")); // re-upload

        assertThat(deriver.derive(atoms, null, Map.of())).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Branch normalization — all synonyms → canonical
    // -------------------------------------------------------------------------

    @Test
    void branchNormalization_allSynonymsCanonicalize() {
        for (String s : List.of("navy", "US Navy", "u.s. navy", "USN", "NAVY USN", "United States Navy")) {
            assertThat(ServiceHistoryReconciler.canonicalBranch(s)).isEqualTo("Navy");
        }
        for (String s : List.of("army", "US Army", "USA", "United States Army")) {
            assertThat(ServiceHistoryReconciler.canonicalBranch(s)).isEqualTo("Army");
        }
        for (String s : List.of("air force", "USAF", "US Air Force")) {
            assertThat(ServiceHistoryReconciler.canonicalBranch(s)).isEqualTo("Air Force");
        }
        for (String s : List.of("marine corps", "marines", "USMC", "US Marine Corps")) {
            assertThat(ServiceHistoryReconciler.canonicalBranch(s)).isEqualTo("Marine Corps");
        }
        for (String s : List.of("coast guard", "USCG")) {
            assertThat(ServiceHistoryReconciler.canonicalBranch(s)).isEqualTo("Coast Guard");
        }
        for (String s : List.of("space force", "USSF")) {
            assertThat(ServiceHistoryReconciler.canonicalBranch(s)).isEqualTo("Space Force");
        }
        // Unknown → title-cased original; null/blank → "Unknown" sentinel.
        assertThat(ServiceHistoryReconciler.canonicalBranch("merchant marine")).isEqualTo("Merchant Marine");
        assertThat(ServiceHistoryReconciler.canonicalBranch(null)).isEqualTo("Unknown");
        assertThat(ServiceHistoryReconciler.canonicalBranch("   ")).isEqualTo("Unknown");
    }

    // -------------------------------------------------------------------------
    // Authority mapping — doc-type → rank ordering
    // -------------------------------------------------------------------------

    @Test
    void authorityMapping_docTypeHierarchy() {
        int dd214 = ServiceHistoryReconciler.authorityFor("DD-214");
        int ngb22 = ServiceHistoryReconciler.authorityFor("NGB-22");
        int svc = ServiceHistoryReconciler.authorityFor("Service Personnel Record");
        int orders = ServiceHistoryReconciler.authorityFor("Deployment Orders");
        int unknown = ServiceHistoryReconciler.authorityFor("Some Random Doc");
        int manual = ServiceHistoryReconciler.authorityFor("manual");

        assertThat(dd214).isGreaterThan(ngb22);
        assertThat(ngb22).isGreaterThan(svc);
        assertThat(svc).isGreaterThan(orders);
        assertThat(orders).isGreaterThan(unknown);
        assertThat(unknown).isGreaterThan(manual);
        // A null/blank classification is "unknown" — above manual, never crashes.
        assertThat(ServiceHistoryReconciler.authorityFor(null)).isEqualTo(unknown);
        assertThat(ServiceHistoryReconciler.authorityFor("")).isEqualTo(unknown);
    }

    // -------------------------------------------------------------------------
    // Span math
    // -------------------------------------------------------------------------

    @Test
    void spanYears_unknownBoundsContributeZero_neverNegative() {
        assertThat(ServiceHistoryReconciler.spanYears("2001-06-01", "2009-06-01")).isEqualTo(8);
        assertThat(ServiceHistoryReconciler.spanYears(null, "2009-06-01")).isZero();
        assertThat(ServiceHistoryReconciler.spanYears("2001-06-01", null)).isZero();
        assertThat(ServiceHistoryReconciler.spanYears("2009-06-01", "2001-06-01")).isZero(); // min 0
    }

    // -------------------------------------------------------------------------
    // Empty input
    // -------------------------------------------------------------------------

    @Test
    void emptyInput_yieldsNoConclusions() {
        assertThat(new ServiceHistoryReconciler().reconcile(List.of(), Map.of())).isEmpty();
        assertThat(new ServiceHistoryReconciler().reconcile(null, null)).isEmpty();
    }

    @Test
    void sourcesCarryRawValuesForDrillDown() {
        List<Atom> atoms = new ArrayList<>(record(901L, "US Navy", "2001-06-01", "2009-06-01", "9213/9211", "CTI1"));
        List<ServicePeriodDto> out = reconcilingDeriver().derive(atoms, null, Map.of(901L, "DD-214"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSources()).hasSize(1);
        ServiceSourceDto src = out.get(0).getSources().get(0);
        assertThat(src.getEvidenceId()).isEqualTo(901L);
        assertThat(src.getDocType()).isEqualTo("DD-214");
        assertThat(src.getRawBranch()).isEqualTo("US Navy");        // raw, un-canonicalized
        assertThat(src.getRawStart()).isEqualTo("2001-06-01");
        assertThat(src.getRawMos()).isEqualTo("9213/9211");
        assertThat(src.getRawRank()).isEqualTo("CTI1");
    }
}
