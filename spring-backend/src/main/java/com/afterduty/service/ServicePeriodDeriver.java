package com.afterduty.service;

import com.afterduty.dto.ServicePeriodDto;
import com.afterduty.model.Atom;
import com.afterduty.model.Claim;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.ServiceHistoryOverride;
import com.afterduty.model.ServiceHistoryResolution;
import com.afterduty.model.ServiceProfile;
import com.afterduty.model.User;
import com.afterduty.repository.AtomRepository;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceRepository;
import com.afterduty.repository.ServiceHistoryOverrideRepository;
import com.afterduty.repository.ServiceHistoryResolutionRepository;
import com.afterduty.repository.ServiceProfileRepository;
import com.afterduty.service.ServiceHistoryReconciler.RawPeriod;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministically derives {@code servicePeriods[]} for {@code GET /api/auth/profile}
 * from facts the extraction pipeline has ALREADY persisted — no LLM calls at read time.
 *
 * <p><b>What extraction actually stores.</b> Both the specialized pipeline
 * ({@code ServiceRecordExtractorService.parseResponse}, saved by
 * {@code ExtractionStateMachine.saveAtoms} with {@code createdBy="ai:extraction-service-record"})
 * and the single-pass path ({@code SinglePassExtractionService} routes its
 * {@code service_records} object through the same parser) flatten the per-document
 * service record into {@code atoms} rows of type {@code service_record_detail} with
 * labeled string values:
 * <ul>
 *   <li>{@code "Branch of Service: Army"}</li>
 *   <li>{@code "Rank: SGT"}, {@code "Pay Grade: E-5"}</li>
 *   <li>{@code "MOS/Rating/AFSC: 11B"}</li>
 *   <li>{@code "Discharge Type: honorable"}, {@code "Character of Service: honorable"}</li>
 *   <li>{@code "Service Period: Enlistment: 2001-05-14 | Separation: 2005-08-30 | Total Years: 4"}</li>
 * </ul>
 * The structured JSON object itself is NOT persisted anywhere, so these atoms are the
 * most reliable stored form. Each atom carries the owning {@code evidenceId}; one
 * document-level service record therefore equals one evidence group.
 *
 * <p><b>Derivation rules (all deterministic string work):</b>
 * <ul>
 *   <li>One period per evidence group that yields a branch or at least one date.</li>
 *   <li>{@code enlistment_date -> startDate}, {@code separation_date -> endDate}
 *       (only strict {@code YYYY-MM-DD} values pass; anything else is null).</li>
 *   <li>Component: branch/discharge text containing {@code "national guard"} → guard,
 *       {@code "reserve"} → reserve, else {@code "active"} when branch known, null otherwise.</li>
 *   <li>The raw per-document candidate periods (each tagged with its owning
 *       evidenceId) + the manual ServiceProfile row feed
 *       {@link ServiceHistoryReconciler}, which normalizes the branch, clusters
 *       overlapping/adjacent periods within a branch+component into ONE conclusion
 *       per real enlistment, and resolves each field by document-type authority —
 *       fixing the six-duplicate + "24 years total" bug. Gated by
 *       {@code va-claim.service-history.reconcile} (default ON).</li>
 *   <li>Flag OFF ⇒ legacy behavior: re-uploads dedupe on the EXACT key branch
 *       (case-insensitive) + startDate + endDate, and the manual row collapses onto
 *       a document period with the same branch+dates (document wins — richer).</li>
 *   <li>Ordered newest-first by startDate; null start sorts last.</li>
 * </ul>
 *
 * <p>Only live atoms are read ({@code supersededBy is null}) so a re-extraction never
 * double-counts, and only the veteran's OWN claims are consulted — this feeds an
 * account-level endpoint that never honors {@code X-View-As}.
 */
@Service
public class ServicePeriodDeriver {

    /** Atom type written by the service-record extractors. */
    static final String ATOM_TYPE = "service_record_detail";

    // Labeled-value prefixes exactly as emitted by ServiceRecordExtractorService.
    private static final String PREFIX_BRANCH = "Branch of Service:";
    private static final String PREFIX_RANK = "Rank:";
    private static final String PREFIX_MOS = "MOS/Rating/AFSC:";
    private static final String PREFIX_DISCHARGE = "Discharge Type:";
    private static final String PREFIX_CHARACTER = "Character of Service:";
    private static final String PREFIX_PERIOD = "Service Period:";

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ENLISTMENT = Pattern.compile("Enlistment:\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern SEPARATION = Pattern.compile("Separation:\\s*(\\d{4}-\\d{2}-\\d{2})");

    /** Newest-first by startDate (ISO strings compare lexicographically); null start last.
     *  Ties broken by endDate (newest first, null last) then branch for stability. */
    private static final Comparator<ServicePeriodDto> NEWEST_FIRST =
            Comparator.comparing(ServicePeriodDto::getStartDate,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ServicePeriodDto::getEndDate,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ServicePeriodDto::getBranch,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    private final ClaimRepository claimRepository;
    private final AtomRepository atomRepository;
    private final ServiceProfileRepository serviceProfileRepository;
    private final EvidenceRepository evidenceRepository;
    private final ServiceHistoryReconciler reconciler;
    private final ServiceHistoryOverrideRepository overrideRepository;
    private final ServiceHistoryResolutionRepository resolutionRepository;

    /**
     * Master switch for Service History reconciliation (design 2026-07-05).
     * DEFAULT ON: raw per-document periods are normalized + clustered into one
     * conclusion per real enlistment (fixes the six-duplicates + "24 years" bug).
     * ROLLBACK: set {@code SERVICE_HISTORY_RECONCILE=false} (no code deploy) to
     * fall back to today's exact-dedupe behavior byte-for-byte.
     */
    @Value("${va-claim.service-history.reconcile:true}")
    private boolean reconcileEnabled = true;

    @Autowired
    public ServicePeriodDeriver(ClaimRepository claimRepository,
                                AtomRepository atomRepository,
                                ServiceProfileRepository serviceProfileRepository,
                                EvidenceRepository evidenceRepository,
                                ServiceHistoryReconciler reconciler,
                                ServiceHistoryOverrideRepository overrideRepository,
                                ServiceHistoryResolutionRepository resolutionRepository) {
        this.claimRepository = claimRepository;
        this.atomRepository = atomRepository;
        this.serviceProfileRepository = serviceProfileRepository;
        this.evidenceRepository = evidenceRepository;
        this.reconciler = reconciler;
        this.overrideRepository = overrideRepository;
        this.resolutionRepository = resolutionRepository;
    }

    /** Convenience constructor for the pure-derivation unit tests (no override /
     *  resolution repos — those layers are exercised via the map-taking
     *  {@code derive} overloads). Production wiring uses the 7-arg constructor. */
    ServicePeriodDeriver(ClaimRepository claimRepository,
                         AtomRepository atomRepository,
                         ServiceProfileRepository serviceProfileRepository,
                         EvidenceRepository evidenceRepository,
                         ServiceHistoryReconciler reconciler) {
        this(claimRepository, atomRepository, serviceProfileRepository, evidenceRepository,
                reconciler, null, null);
    }

    /** Test/override hook for the reconcile flag (Spring sets it via @Value). */
    void setReconcileEnabled(boolean reconcileEnabled) {
        this.reconcileEnabled = reconcileEnabled;
    }

    /** Derive periods for the given user's OWN claims + manual profile row, with
     *  any persisted veteran overrides + LLM resolutions re-applied at their
     *  authority (override > resolution > deterministic). Both are keyed by the
     *  reconciler's stable cluster key, so a persisted correction re-attaches to
     *  the right conclusion after re-derivation. Repos may be null (unit tests /
     *  flag-only paths) ⇒ empty maps ⇒ plain reconciliation. */
    public List<ServicePeriodDto> deriveForUser(User user) {
        List<Atom> atoms = new ArrayList<>();
        for (Claim claim : claimRepository.findByUserIdOrderByCreatedAtDesc(user.getId())) {
            atoms.addAll(atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()));
        }
        ServiceProfile manual = serviceProfileRepository.findByUserId(user.getId()).orElse(null);
        Map<Long, String> classifications = loadClassifications(atoms);
        Map<String, ServiceHistoryOverride> overrides = loadOverrides(user.getId());
        Map<String, ServiceHistoryResolution> resolutions = loadResolutions(user.getId());
        return derive(atoms, manual, classifications, overrides, resolutions);
    }

    /** The raw reconciliation inputs for one user — the pre-cluster candidate
     *  periods (each tagged with its evidenceId) plus their doc-type classifications.
     *  The pipeline-time conflict adjudicator feeds these to
     *  {@link ServiceHistoryReconciler#detectConflicts}. */
    public record RawInputs(List<RawPeriod> raw, Map<Long, String> classifications) {
    }

    /** Build the raw reconciliation inputs for one user's OWN claims + manual row —
     *  the same corpus {@link #deriveForUser} reconciles, exposed so the pipeline can
     *  scan for genuine conflicts without re-implementing extraction. */
    public RawInputs buildRawInputsForUser(User user) {
        List<Atom> atoms = new ArrayList<>();
        for (Claim claim : claimRepository.findByUserIdOrderByCreatedAtDesc(user.getId())) {
            atoms.addAll(atomRepository.findByClaimIdAndSupersededByIsNull(claim.getId()));
        }
        ServiceProfile manual = serviceProfileRepository.findByUserId(user.getId()).orElse(null);
        Map<Long, String> classifications = loadClassifications(atoms);
        return new RawInputs(buildRawPeriods(atoms, manual), classifications);
    }

    /** ONE user's overrides keyed by clusterKey. Null repo (tests) ⇒ empty. */
    private Map<String, ServiceHistoryOverride> loadOverrides(Long userId) {
        Map<String, ServiceHistoryOverride> byKey = new HashMap<>();
        if (overrideRepository == null) return byKey;
        for (ServiceHistoryOverride o : overrideRepository.findByUserId(userId)) {
            if (o.getClusterKey() != null) byKey.put(o.getClusterKey(), o);
        }
        return byKey;
    }

    /** ONE user's persisted LLM resolutions keyed by clusterKey. Null repo ⇒ empty. */
    private Map<String, ServiceHistoryResolution> loadResolutions(Long userId) {
        Map<String, ServiceHistoryResolution> byKey = new HashMap<>();
        if (resolutionRepository == null) return byKey;
        for (ServiceHistoryResolution r : resolutionRepository.findByUserId(userId)) {
            if (r.getClusterKey() != null) byKey.put(r.getClusterKey(), r);
        }
        return byKey;
    }

    /** Look up EvidenceItem.aiClassification for every evidenceId the atoms touch,
     *  so the reconciler can weigh sources by document type. Missing rows are simply
     *  absent from the map (⇒ lowest-above-manual authority downstream). */
    private Map<Long, String> loadClassifications(List<Atom> atoms) {
        Map<Long, String> byId = new HashMap<>();
        if (atoms == null || evidenceRepository == null) return byId;
        List<Long> ids = atoms.stream()
                .filter(a -> a != null && a.getEvidenceId() != null && ATOM_TYPE.equals(a.getType()))
                .map(Atom::getEvidenceId)
                .distinct()
                .toList();
        if (ids.isEmpty()) return byId;
        for (EvidenceItem ev : evidenceRepository.findAllById(ids)) {
            if (ev.getId() != null) byId.put(ev.getId(), ev.getAiClassification());
        }
        return byId;
    }

    /**
     * Pure, deterministic derivation with no classification lookup — retained for
     * callers/tests that don't supply doc types. Delegates with an empty map.
     */
    public List<ServicePeriodDto> derive(List<Atom> atoms, ServiceProfile manualProfile) {
        return derive(atoms, manualProfile, Map.of());
    }

    /**
     * Pure, deterministic derivation — the unit-testable core. {@code atoms} may
     * contain any types; only live handling is the caller's job (pass live atoms).
     *
     * <p>With reconciliation ON (default) the raw per-document candidate periods
     * are normalized + clustered into one conclusion per enlistment; OFF falls back
     * to exact branch+start+end dedupe (today's behavior).
     */
    public List<ServicePeriodDto> derive(List<Atom> atoms, ServiceProfile manualProfile,
                                         Map<Long, String> classifications) {
        return derive(atoms, manualProfile, classifications, Map.of(), Map.of());
    }

    /**
     * Pure, deterministic derivation with authority LAYERING (Service History P3):
     * the reconciled conclusions have any veteran {@code overrides} (top authority)
     * and persisted LLM {@code resolutions} (below overrides, above the raw pick)
     * re-applied, both keyed by the reconciler's stable cluster key. Empty maps ⇒
     * plain reconciliation (identical to the 3-arg overload). Flag OFF ⇒ legacy
     * exact-dedupe (override/resolution layering only exists on the reconcile path).
     */
    public List<ServicePeriodDto> derive(List<Atom> atoms, ServiceProfile manualProfile,
                                         Map<Long, String> classifications,
                                         Map<String, ServiceHistoryOverride> overrides,
                                         Map<String, ServiceHistoryResolution> resolutions) {
        List<RawPeriod> raw = buildRawPeriods(atoms, manualProfile);

        if (reconcileEnabled) {
            List<ServicePeriodDto> conclusions =
                    reconciler.reconcile(raw, classifications, overrides, resolutions);
            conclusions.sort(NEWEST_FIRST);
            return conclusions;
        }
        return legacyExactDedupe(raw);
    }

    /**
     * Today's behavior (flag OFF): dedupe on the exact key branch(ci)+start+end,
     * keeping the first seen; manual row collapses onto a matching document period.
     */
    private List<ServicePeriodDto> legacyExactDedupe(List<RawPeriod> raw) {
        Map<String, ServicePeriodDto> deduped = new LinkedHashMap<>();
        for (RawPeriod rp : raw) {
            deduped.putIfAbsent(rp.period().dedupeKey(), rp.period());
        }
        List<ServicePeriodDto> periods = new ArrayList<>(deduped.values());
        periods.sort(NEWEST_FIRST);
        return periods;
    }

    /**
     * The raw candidate periods (each tagged with its owning evidenceId) from a
     * corpus of atoms + the manual profile row — the pre-cluster input shared by
     * {@link #derive} and {@link #buildRawInputsForUser}. Groups service-record
     * atoms by owning document (LinkedHashMap keeps deterministic order; a null
     * evidenceId forms its own group), builds one period per group, and appends the
     * manual/self-statement row (null evidenceId) when present.
     */
    private List<RawPeriod> buildRawPeriods(List<Atom> atoms, ServiceProfile manualProfile) {
        Map<Long, List<Atom>> byEvidence = new LinkedHashMap<>();
        if (atoms != null) {
            for (Atom atom : atoms) {
                if (atom == null || !ATOM_TYPE.equals(atom.getType())) continue;
                if (atom.getValue() == null) continue;
                byEvidence.computeIfAbsent(atom.getEvidenceId(), k -> new ArrayList<>()).add(atom);
            }
        }
        List<RawPeriod> raw = new ArrayList<>();
        for (Map.Entry<Long, List<Atom>> entry : byEvidence.entrySet()) {
            ServicePeriodDto period = periodFromGroup(entry.getValue());
            if (period != null) {
                raw.add(new RawPeriod(entry.getKey(), period));
            }
        }
        ServicePeriodDto manual = periodFromManual(manualProfile);
        if (manual != null) {
            raw.add(new RawPeriod(null, manual));
        }
        return raw;
    }

    // -------------------------------------------------------------------------
    // Document-derived periods
    // -------------------------------------------------------------------------

    /** Build one period from one document's service_record_detail atoms; null when
     *  the group yields neither a branch nor a date (nothing period-shaped). */
    private ServicePeriodDto periodFromGroup(List<Atom> group) {
        String branch = null, rank = null, mos = null, start = null, end = null;
        StringBuilder dischargeText = new StringBuilder();

        for (Atom atom : group) {
            String value = atom.getValue().strip();
            if (value.startsWith(PREFIX_BRANCH)) {
                branch = firstNonBlank(branch, valueAfter(value, PREFIX_BRANCH));
            } else if (value.startsWith(PREFIX_RANK)) {
                rank = firstNonBlank(rank, valueAfter(value, PREFIX_RANK));
            } else if (value.startsWith(PREFIX_MOS)) {
                mos = firstNonBlank(mos, valueAfter(value, PREFIX_MOS));
            } else if (value.startsWith(PREFIX_DISCHARGE) || value.startsWith(PREFIX_CHARACTER)) {
                dischargeText.append(' ').append(value);
            } else if (value.startsWith(PREFIX_PERIOD)) {
                if (start == null) start = firstMatch(ENLISTMENT, value);
                if (end == null) end = firstMatch(SEPARATION, value);
            }
        }

        if (branch == null && start == null && end == null) return null;
        String component = detectComponent(branch, dischargeText.toString());
        return new ServicePeriodDto(branch, component, start, end, mos, rank,
                ServicePeriodDto.SOURCE_DOCUMENTS);
    }

    // -------------------------------------------------------------------------
    // Manual ServiceProfile row
    // -------------------------------------------------------------------------

    /** Map the veteran-entered ServiceProfile row to a period; null when the row is
     *  absent or has neither a branch nor a parseable date. */
    private ServicePeriodDto periodFromManual(ServiceProfile profile) {
        if (profile == null) return null;
        String branch = blankToNull(profile.getBranch());
        String start = isoOrNull(profile.getServiceStart());
        String end = isoOrNull(profile.getServiceEnd());
        if (branch == null && start == null && end == null) return null;
        String component = detectComponent(branch, null);
        return new ServicePeriodDto(branch, component, start, end,
                blankToNull(profile.getMos()), null, ServicePeriodDto.SOURCE_MANUAL);
    }

    // -------------------------------------------------------------------------
    // Deterministic string rules
    // -------------------------------------------------------------------------

    /**
     * Component from branch/discharge text (pinned contract): text containing
     * "national guard" → guard, "reserve" → reserve, else "active" when the
     * branch is known, null otherwise. Checked on the combined text so
     * "Army National Guard" (branch) and "Transferred to Reserve" (discharge
     * remarks) both classify; "national guard" wins over "reserve" because Guard
     * records routinely mention the Reserve component boilerplate.
     */
    static String detectComponent(String branch, String dischargeText) {
        String text = ((branch == null ? "" : branch) + " " + (dischargeText == null ? "" : dischargeText))
                .toLowerCase(Locale.ROOT);
        if (text.contains("national guard")) return ServicePeriodDto.COMPONENT_GUARD;
        if (text.contains("reserve")) return ServicePeriodDto.COMPONENT_RESERVE;
        return branch != null ? ServicePeriodDto.COMPONENT_ACTIVE : null;
    }

    /** Strict YYYY-MM-DD or null — the wire contract only carries ISO dates. */
    static String isoOrNull(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        return ISO_DATE.matcher(s).matches() ? s : null;
    }

    private static String valueAfter(String value, String prefix) {
        return blankToNull(value.substring(prefix.length()));
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String firstNonBlank(String current, String candidate) {
        return current != null ? current : candidate;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String stripped = s.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
