package com.afterduty.service;

import com.afterduty.dto.ServicePeriodDto;
import com.afterduty.dto.ServiceSourceDto;
import com.afterduty.model.ServiceHistoryOverride;
import com.afterduty.model.ServiceHistoryResolution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reconciles the RAW per-document service periods {@code ServicePeriodDeriver}
 * extracts into ONE conclusion per real enlistment — deterministically, no LLM,
 * no read-time network (design 2026-07-05-service-history-reconciliation).
 *
 * <p><b>Why.</b> Government records are duplicative by nature: a single 2001–2009
 * Navy enlistment surfaces as {@code "NAVY USN"} / {@code "US Navy"} / {@code "Navy"},
 * with different MOS lists, a rate on some rows, and end dates 2008 vs 2009. Exact
 * dedupe (branch string + startDate + endDate) never collapses them, so the profile
 * showed six rows and summed the overlapping spans into a bogus "~24 years". This
 * reconciler normalizes the branch, clusters overlapping/adjacent periods within the
 * same branch+component into one enlistment, resolves each field by VA-style
 * document-type precedence, and reports the correct per-conclusion span.
 *
 * <p><b>Merge predicate (conservative — a false merge that hides a real second
 * enlistment is worse than a missed merge).</b> Two raw periods join iff:
 * <ol>
 *   <li>they share the same CANONICAL branch (post-normalization), AND</li>
 *   <li>they share the same component (active/guard/reserve; null matches any —
 *       an undated branch-only row rarely carries a component), AND</li>
 *   <li>their date intervals OVERLAP, are ADJACENT (gap ≤ {@link #ADJACENCY_DAYS},
 *       ~1yr, covering back-to-back re-enlistments and 2008-vs-2009 end-date jitter),
 *       or one interval is OPEN/UNKNOWN (a missing start or end, or a branch-only
 *       row with no dates at all — it is "consistent with" any dated period in the
 *       same branch+component group and folds in).</li>
 * </ol>
 * Two genuinely separate enlistments (same branch, non-overlapping, gap &gt; ~1yr)
 * stay separate. Different canonical branches or different components NEVER merge.
 * Clustering is greedy single-linkage: a period is added to the first existing
 * cluster it is compatible with (widening that cluster's envelope), else it opens a
 * new cluster — so a chain of pairwise-adjacent records transitively coalesces.
 */
@Service
public class ServiceHistoryReconciler {

    private static final Logger log = LoggerFactory.getLogger(ServiceHistoryReconciler.class);

    /** Max gap (days) between two dated intervals still treated as one enlistment.
     *  ~13 months: absorbs back-to-back re-enlistment paperwork and the common
     *  2008-vs-2009 separation-date disagreement across records of one hitch. */
    static final int ADJACENCY_DAYS = 400;

    // ---- Authority ranks (VA evidence weight, higher wins). ------------------
    static final int RANK_DD214 = 100;      // DD-214 / separation — top
    static final int RANK_NGB22 = 90;       // NGB-22 (Guard)
    static final int RANK_SERVICE_REC = 70; // service treatment / personnel record
    static final int RANK_ORDERS = 50;      // orders
    static final int RANK_UNKNOWN = 30;     // classified evidence we can't rank — above manual
    static final int RANK_MANUAL = 10;      // self-statement / manual profile — lowest

    static final String DOCTYPE_MANUAL = "manual";

    // ---- Branch synonym → canonical map (case-insensitive keys). -------------
    private static final Map<String, String> BRANCH_CANON = new LinkedHashMap<>();
    static {
        canon("Navy", "navy", "us navy", "u.s. navy", "usn", "navy usn", "united states navy");
        canon("Army", "army", "us army", "u.s. army", "usa", "united states army");
        canon("Air Force", "air force", "usaf", "us air force", "u.s. air force", "united states air force");
        canon("Marine Corps", "marine corps", "marines", "usmc", "us marine corps",
                "u.s. marine corps", "united states marine corps");
        canon("Coast Guard", "coast guard", "uscg", "us coast guard", "united states coast guard");
        canon("Space Force", "space force", "ussf", "us space force", "united states space force");
    }

    private static void canon(String canonical, String... synonyms) {
        for (String s : synonyms) {
            BRANCH_CANON.put(s, canonical);
        }
    }

    /**
     * A single raw candidate period tagged with its owning evidenceId — the
     * pre-reconciliation input the deriver feeds in. evidenceId is null for the
     * manual/self-statement row.
     */
    public record RawPeriod(Long evidenceId, ServicePeriodDto period) {
    }

    /**
     * Reconcile raw candidate periods into conclusions (one per enlistment).
     *
     * @param raw            raw candidate periods (each tagged with evidenceId), in
     *                       any order; may be empty.
     * @param classifications evidenceId → EvidenceItem.aiClassification, used for
     *                       doc-type authority. Missing/null ⇒ lowest-above-manual.
     *                       A null map is treated as empty.
     * @return reconciled conclusions; each carries the legacy fields plus
     *         {@code sources}, {@code reasoning}, {@code totalYears}. Never null.
     */
    public List<ServicePeriodDto> reconcile(List<RawPeriod> raw, Map<Long, String> classifications) {
        return reconcile(raw, classifications, Map.of(), Map.of());
    }

    /**
     * Reconcile with authority LAYERING (Service History P3). Beyond the raw
     * document reconciliation, apply — in strict precedence, highest first:
     * <ol>
     *   <li><b>Veteran override</b> ({@code overrides} keyed by clusterKey): the
     *       set fields win over everything, tagged "Corrected by you". Always
     *       available (unflagged).</li>
     *   <li><b>LLM resolution</b> ({@code resolutions} keyed by clusterKey): a
     *       persisted, pipeline-time adjudication of a genuine equal-authority
     *       conflict — applied ONLY to a field the veteran did NOT override, and
     *       only when its evidence fingerprint still matches this cluster (stale
     *       resolutions are ignored).</li>
     *   <li><b>Deterministic pick</b>: today's doc-type-authority resolution.</li>
     * </ol>
     * Both maps may be empty (the plain read path passes empty ⇒ identical to the
     * 2-arg overload). Keyed by the STABLE cluster key so a persisted correction
     * re-attaches to the right conclusion after re-derivation.
     */
    public List<ServicePeriodDto> reconcile(List<RawPeriod> raw, Map<Long, String> classifications,
                                            Map<String, ServiceHistoryOverride> overrides,
                                            Map<String, ServiceHistoryResolution> resolutions) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, ServiceHistoryOverride> ov = overrides != null ? overrides : Map.of();
        Map<String, ServiceHistoryResolution> res = resolutions != null ? resolutions : Map.of();

        List<List<Candidate>> clusters = clusterize(raw, classifications);
        List<ServicePeriodDto> conclusions = new ArrayList<>();
        for (List<Candidate> cluster : clusters) {
            String key = clusterKey(cluster);
            ServicePeriodDto dto = concludeCluster(cluster);
            dto.setClusterKey(key);
            applyResolution(dto, cluster, res.get(key));
            applyOverride(dto, ov.get(key));
            conclusions.add(dto);
        }
        return conclusions;
    }

    /** Cluster raw periods into enlistments (see class-doc merge predicate). The
     *  shared clustering used by both {@code reconcile} and the conflict scan. */
    private List<List<Candidate>> clusterize(List<RawPeriod> raw, Map<Long, String> classifications) {
        Map<Long, String> classes = classifications != null ? classifications : Map.of();

        // Wrap each raw period with its resolved canonical branch + authority so
        // clustering and field-resolution work off pre-computed values.
        List<Candidate> candidates = new ArrayList<>();
        for (RawPeriod rp : raw) {
            if (rp == null || rp.period() == null) continue;
            candidates.add(Candidate.of(rp, classes));
        }

        // Greedy single-linkage clustering (see class-doc merge predicate).
        List<List<Candidate>> clusters = new ArrayList<>();
        for (Candidate c : candidates) {
            List<Candidate> target = null;
            for (List<Candidate> cluster : clusters) {
                if (clusterAccepts(cluster, c)) {
                    target = cluster;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                clusters.add(target);
            }
            target.add(c);
        }
        return clusters;
    }

    /**
     * Does {@code c} belong with an existing {@code cluster}? True iff it is
     * compatible (same canonical branch + component, and date-consistent) with at
     * least ONE member — single-linkage, so transitively-adjacent records coalesce.
     */
    private boolean clusterAccepts(List<Candidate> cluster, Candidate c) {
        for (Candidate member : cluster) {
            if (compatible(member, c)) return true;
        }
        return false;
    }

    /** The pairwise merge predicate (class-doc). Conservative: same branch AND
     *  same component (null wildcards) AND date overlap/adjacency/openness. */
    private boolean compatible(Candidate a, Candidate b) {
        if (!a.canonicalBranch.equals(b.canonicalBranch)) return false;
        if (!componentsCompatible(a.component, b.component)) return false;
        return datesConsistent(a, b);
    }

    /** Components merge only when equal, or when at least one is unknown (null). */
    private boolean componentsCompatible(String x, String y) {
        return x == null || y == null || x.equals(y);
    }

    /**
     * Date-consistency: an open/unknown interval (missing start OR end) is
     * consistent with anything in the same branch+component group; two fully-dated
     * intervals must overlap or sit within {@link #ADJACENCY_DAYS} of each other.
     */
    private boolean datesConsistent(Candidate a, Candidate b) {
        boolean aOpen = a.start == null || a.end == null;
        boolean bOpen = b.start == null || b.end == null;
        if (aOpen || bOpen) {
            // At least one side is open/partial. If BOTH carry a bound we can still
            // sanity-check they don't sit in clearly different eras; otherwise fold in.
            return openConsistent(a, b);
        }
        // Both fully dated: overlap OR gap within adjacency window.
        long gap = intervalGapDays(a.start, a.end, b.start, b.end);
        return gap <= ADJACENCY_DAYS;
    }

    /**
     * Consistency when at least one interval is open. We fold the open record in
     * unless the two carry non-null bounds that are provably in different eras
     * (gap between the closest known endpoints exceeds the adjacency window) — this
     * keeps "US Navy end 2008, no start" folding onto "Navy 2001–2009" while still
     * refusing to swallow, say, a bare "Navy end 1975" onto a 2001–2009 hitch.
     */
    private boolean openConsistent(Candidate a, Candidate b) {
        Integer ay0 = year(a.start), ay1 = year(a.end);
        Integer by0 = year(b.start), by1 = year(b.end);
        // No usable bound on one side (e.g. branch-only, no dates) ⇒ fold in.
        boolean aHasBound = ay0 != null || ay1 != null;
        boolean bHasBound = by0 != null || by1 != null;
        if (!aHasBound || !bHasBound) return true;

        // Both carry at least one year bound: require their known year-spans to be
        // within the adjacency window (≈1yr) of touching. Use the widest known
        // envelope on each side.
        int aLo = min(ay0, ay1), aHi = max(ay0, ay1);
        int bLo = min(by0, by1), bHi = max(by0, by1);
        if (aHi < bLo) return (bLo - aHi) * 366L <= ADJACENCY_DAYS;
        if (bHi < aLo) return (aLo - bHi) * 366L <= ADJACENCY_DAYS;
        return true; // year-envelopes overlap
    }

    /** Build the single conclusion for a resolved cluster. */
    private ServicePeriodDto concludeCluster(List<Candidate> cluster) {
        String canonicalBranch = cluster.get(0).canonicalBranch;

        // Component: the group's — the highest-authority non-null wins, else any non-null.
        String component = pickComponent(cluster);

        // Highest-authority source overall (stable: first at the max rank).
        Candidate top = cluster.get(0);
        for (Candidate c : cluster) {
            if (c.authorityRank > top.authorityRank) top = c;
        }

        // Start = earliest confidently-sourced start; on a genuine conflict prefer
        // the highest-authority source's start, else the earliest (widest range).
        DateChoice startChoice = resolveDate(cluster, true);
        DateChoice endChoice = resolveDate(cluster, false);

        // MOS union across sources, dedup, order preserved.
        String mos = unionMos(cluster);

        // Rank from the highest-authority source that has one.
        String rank = pickRank(cluster);

        String source = pickSource(cluster);

        ServicePeriodDto dto = new ServicePeriodDto(canonicalBranch, component,
                startChoice.date, endChoice.date, mos, rank, source);

        // Sources list (receipts), in cluster (input) order.
        List<ServiceSourceDto> sources = new ArrayList<>();
        for (Candidate c : cluster) {
            sources.add(new ServiceSourceDto(c.evidenceId, c.docType, c.authorityRank,
                    c.rawBranch, c.start, c.end, c.rawMos, c.rawRank));
        }
        dto.setSources(sources);

        int totalYears = spanYears(startChoice.date, endChoice.date);
        dto.setTotalYears(totalYears);
        dto.setReasoning(buildReasoning(cluster, startChoice, endChoice, totalYears));
        return dto;
    }

    // -------------------------------------------------------------------------
    // Stable cluster key (Service History P3) — the identity a persisted override
    // / resolution re-attaches to across re-derives.
    // -------------------------------------------------------------------------

    /**
     * A STABLE, deterministic identity for one reconciled cluster — the key a
     * persisted veteran override / LLM resolution round-trips on so it re-attaches
     * to the SAME real enlistment after re-derivation (new uploads, re-extraction).
     *
     * <p><b>Formula.</b> {@code canonicalBranch|component|earliestStartYear} when
     * the cluster carries any dated source, else a deterministic hash of the member
     * sources' evidenceIds ({@code branch|component|h:<sha8>}). Concretely:
     * <ul>
     *   <li>{@code canonicalBranch} — post-normalization ("NAVY USN"/"US Navy"/
     *       "Navy" all collapse to {@code Navy}), so re-OCR wording changes don't
     *       move the key. Blank ⇒ {@code Unknown}.</li>
     *   <li>{@code component} — {@code active|guard|reserve} or {@code -} when the
     *       cluster has none. Different components never merge, so this never splits
     *       one real enlistment.</li>
     *   <li>{@code earliestStartYear} — the earliest start YEAR across the cluster's
     *       sources (year granularity, so the common 2008-vs-2009 separation-date
     *       jitter and day-level OCR drift don't move it). {@code ?} when no source
     *       is dated.</li>
     *   <li>Undated-only cluster fallback — no start year at all ⇒ the year segment
     *       is {@code h:<8-hex of SHA-256(sorted evidenceIds)>}. Deterministic for a
     *       fixed set of documents; a brand-new upload changes the set (and so the
     *       key), which is correct — an undated cluster has no other stable anchor.</li>
     * </ul>
     *
     * <p><b>Why stable across re-derive.</b> Every input is a property of the real
     * enlistment, not of transient derivation order: canonical branch is
     * synonym-collapsed, component is fixed, the start YEAR is the min over sources
     * (adding another duplicate of the same hitch can only lower it to the same true
     * enlistment year, and the reconciler already picks the earliest confident start
     * as the conclusion). <b>Why unique across enlistments.</b> Two genuinely
     * different enlistments differ in branch, component, OR start era (they can't
     * merge without one of those matching AND date overlap), so their keys differ.
     */
    private String clusterKey(List<Candidate> cluster) {
        String branch = cluster.get(0).canonicalBranch;
        String component = pickComponent(cluster);
        String comp = component != null ? component : "-";

        Integer earliest = earliestStartYear(cluster);
        String era = earliest != null ? String.valueOf(earliest) : undatedHash(cluster);
        return branch + "|" + comp + "|" + era;
    }

    /** Earliest start YEAR across the cluster's sources, or null when none is dated. */
    private Integer earliestStartYear(List<Candidate> cluster) {
        Integer earliest = null;
        for (Candidate c : cluster) {
            Integer y = year(c.start);
            if (y != null && (earliest == null || y < earliest)) earliest = y;
        }
        return earliest;
    }

    /** Deterministic {@code h:<sha8>} of the cluster's sorted evidenceIds — the
     *  stable anchor for an undated-only cluster (no start year to key on). Null
     *  evidenceIds (the manual row) sort as an empty token so a manual-only undated
     *  cluster still keys deterministically. */
    private String undatedHash(List<Candidate> cluster) {
        List<String> ids = new ArrayList<>();
        for (Candidate c : cluster) {
            ids.add(c.evidenceId != null ? String.valueOf(c.evidenceId) : "manual");
        }
        ids.sort(null);
        String joined = String.join(",", ids);
        return "h:" + sha8(joined);
    }

    private static String sha8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", digest[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; fall back to a stable
            // non-crypto token so key derivation never throws.
            return Integer.toHexString(s.hashCode());
        }
    }

    /**
     * A content fingerprint of the cluster's contributing sources (their sorted
     * {@code evidenceId:start:end} triples). Persisted alongside an LLM resolution
     * so a re-derive can tell whether the evidence behind the adjudicated conflict
     * still matches — if not, the resolution is stale and ignored. Package-visible
     * only for readability; the pipeline gets the fingerprint via {@link Conflict}.
     */
    private String evidenceFingerprint(List<Candidate> cluster) {
        List<String> parts = new ArrayList<>();
        for (Candidate c : cluster) {
            parts.add((c.evidenceId != null ? c.evidenceId : "manual")
                    + ":" + (c.start != null ? c.start : "")
                    + ":" + (c.end != null ? c.end : ""));
        }
        parts.sort(null);
        return sha8(String.join("|", parts));
    }

    // -------------------------------------------------------------------------
    // Conflict detection (Service History P3 Part B) — flag a GENUINE conflict:
    // two EQUAL-authority sources with contradictory dates. The deterministic pick
    // stays the default; the pipeline adjudicates only these.
    // -------------------------------------------------------------------------

    /**
     * One flagged conflict inside a cluster: two sources of the SAME (top) authority
     * rank carry different values for a date bound. Carries the clusterKey (to
     * persist/read the resolution), the field, the tied candidate values, and this
     * cluster's evidence fingerprint (for staleness).
     */
    public record Conflict(String clusterKey, String field, String valueA, String valueB,
                           int authorityRank, String evidenceFingerprint) {
    }

    /**
     * Scan for genuine conflicts across all clusters (pipeline-time entry point).
     * A conflict fires ONLY when two sources at the SAME highest rank that carries
     * the bound disagree — a DD-214 (rank 100) beating an orders (rank 50) is NOT a
     * conflict (authority resolves it deterministically); two DD-214s disagreeing on
     * the end date IS. Returns an empty list when nothing is genuinely ambiguous, so
     * the caller makes NO LLM call in the common case.
     */
    public List<Conflict> detectConflicts(List<RawPeriod> raw, Map<Long, String> classifications) {
        List<Conflict> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (List<Candidate> cluster : clusterize(raw, classifications)) {
            String key = clusterKey(cluster);
            String fp = evidenceFingerprint(cluster);
            Conflict start = equalAuthorityConflict(cluster, true, key, fp);
            if (start != null) out.add(start);
            Conflict end = equalAuthorityConflict(cluster, false, key, fp);
            if (end != null) out.add(end);
        }
        return out;
    }

    /**
     * The conflict predicate for one bound: among the sources that carry it, find
     * the HIGHEST authority rank; if two-or-more sources at THAT rank carry
     * DIFFERENT values, it's a genuine equal-authority contradiction. Ranks below
     * the top are irrelevant (authority already resolves them). Null ⇒ no conflict.
     */
    private Conflict equalAuthorityConflict(List<Candidate> cluster, boolean isStart,
                                            String key, String fp) {
        int topRank = Integer.MIN_VALUE;
        for (Candidate c : cluster) {
            String v = isStart ? c.start : c.end;
            if (v != null && c.authorityRank > topRank) topRank = c.authorityRank;
        }
        if (topRank == Integer.MIN_VALUE) return null; // no dated source

        String first = null;
        for (Candidate c : cluster) {
            String v = isStart ? c.start : c.end;
            if (v == null || c.authorityRank != topRank) continue;
            if (first == null) {
                first = v;
            } else if (!first.equals(v)) {
                return new Conflict(key, isStart ? "start" : "end", first, v, topRank, fp);
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Authority layering (Service History P3) — resolution below override.
    // -------------------------------------------------------------------------

    /**
     * Apply a persisted LLM resolution to a conclusion — BELOW a veteran override
     * (applied after this) and ABOVE the deterministic pick. Ignored when: absent,
     * for a field this cluster no longer has, or STALE (its evidence fingerprint no
     * longer matches this cluster's sources — the documents moved since it was
     * adjudicated). Adds an "Adjudicated" reasoning line when applied.
     */
    private void applyResolution(ServicePeriodDto dto, List<Candidate> cluster,
                                 ServiceHistoryResolution resolution) {
        if (resolution == null || resolution.getResolvedField() == null
                || resolution.getResolvedValue() == null) {
            return;
        }
        // Staleness guard: only honor a resolution whose evidence fingerprint still
        // matches this cluster (null fingerprint on the row ⇒ legacy, honor it).
        String rowFp = resolution.getEvidenceFingerprint();
        if (rowFp != null && !rowFp.equals(evidenceFingerprint(cluster))) {
            return;
        }
        String field = resolution.getResolvedField();
        String value = resolution.getResolvedValue();
        boolean applied = false;
        if (ServiceHistoryResolution.FIELD_START.equals(field)) {
            dto.setStartDate(value);
            applied = true;
        } else if (ServiceHistoryResolution.FIELD_END.equals(field)) {
            dto.setEndDate(value);
            applied = true;
        }
        if (applied) {
            dto.setTotalYears(spanYears(dto.getStartDate(), dto.getEndDate()));
            String why = resolution.getReasoning() != null && !resolution.getReasoning().isBlank()
                    ? resolution.getReasoning().strip()
                    : "equal-authority conflict adjudicated";
            dto.setReasoning(appendLine(dto.getReasoning(),
                    "Adjudicated " + field + " " + value + " (" + why + ")."));
        }
    }

    /**
     * Apply a veteran override at TOP authority — the set fields win over the
     * documents (and over any resolution). Every override field is nullable; only
     * non-null fields override. Adds a "Corrected by you" reasoning line + marks the
     * dto so the web can badge it, and recomputes totalYears when a date changed.
     */
    private void applyOverride(ServicePeriodDto dto, ServiceHistoryOverride override) {
        if (override == null || !override.hasAnyField()) return;
        if (override.getBranch() != null) dto.setBranch(override.getBranch());
        if (override.getComponent() != null) dto.setComponent(override.getComponent());
        if (override.getStartDate() != null) dto.setStartDate(override.getStartDate());
        if (override.getEndDate() != null) dto.setEndDate(override.getEndDate());
        if (override.getMos() != null) dto.setMos(override.getMos());
        if (override.getRank() != null) dto.setRank(override.getRank());
        dto.setTotalYears(spanYears(dto.getStartDate(), dto.getEndDate()));
        dto.setReasoning(appendLine(dto.getReasoning(), "Corrected by you."));
    }

    /** Append a sentence to a reasoning trace (space-joined), null-safe. */
    private static String appendLine(String existing, String line) {
        if (existing == null || existing.isBlank()) return line;
        String e = existing.strip();
        return e.endsWith(".") ? e + " " + line : e + ". " + line;
    }

    private String pickComponent(List<Candidate> cluster) {
        String best = null;
        int bestRank = Integer.MIN_VALUE;
        for (Candidate c : cluster) {
            if (c.component != null && c.authorityRank > bestRank) {
                best = c.component;
                bestRank = c.authorityRank;
            }
        }
        return best;
    }

    private String pickRank(List<Candidate> cluster) {
        String best = null;
        int bestRank = Integer.MIN_VALUE;
        for (Candidate c : cluster) {
            if (c.rawRank != null && c.authorityRank > bestRank) {
                best = c.rawRank;
                bestRank = c.authorityRank;
            }
        }
        return best;
    }

    /** source = highest-authority contributor's source; ties keep first seen. */
    private String pickSource(List<Candidate> cluster) {
        Candidate best = cluster.get(0);
        for (Candidate c : cluster) {
            if (c.authorityRank > best.authorityRank) best = c;
        }
        return best.source;
    }

    private String unionMos(List<Candidate> cluster) {
        LinkedHashSet<String> mosSet = new LinkedHashSet<>();
        for (Candidate c : cluster) {
            if (c.rawMos == null) continue;
            // A source's MOS field may itself be a list ("9213/9211" or "063, 221, 232").
            for (String part : c.rawMos.split("[/,]")) {
                String p = part.strip();
                if (!p.isEmpty()) mosSet.add(p);
            }
        }
        if (mosSet.isEmpty()) return null;
        return String.join(", ", mosSet);
    }

    /**
     * Resolve start ({@code isStart=true}) or end. Among sources that carry the
     * bound: if a UNIQUE highest-authority source carries it, its value wins;
     * otherwise (a tie at the top rank, or no classification edge) take the widest
     * range (earliest start / latest end). A "conflict" is flagged whenever the
     * chosen value disagrees with ANY other dated source for this bound — that is
     * the case the reasoning trace calls out (e.g. end 2009 from DD-214 chosen over
     * 2008 from orders), regardless of whether the winner also happened to be the
     * widest.
     */
    private DateChoice resolveDate(List<Candidate> cluster, boolean isStart) {
        Candidate widest = null;      // earliest start / latest end
        Candidate authoritative = null; // unique highest-authority carrier of the bound
        int authRank = Integer.MIN_VALUE;
        int authCount = 0;
        for (Candidate c : cluster) {
            String v = isStart ? c.start : c.end;
            if (v == null) continue;
            if (widest == null || wider(v, widthValue(widest, isStart), isStart)) {
                widest = c;
            }
            if (c.authorityRank > authRank) {
                authRank = c.authorityRank;
                authoritative = c;
                authCount = 1;
            } else if (c.authorityRank == authRank) {
                authCount++;
            }
        }
        if (widest == null) {
            return new DateChoice(null, null, false); // no dated source
        }
        Candidate winner;
        String chosen;
        if (authoritative != null && authCount == 1) {
            winner = authoritative;
            chosen = isStart ? authoritative.start : authoritative.end;
        } else {
            // Tie at the top authority (or no classification edge): take the widest.
            winner = widest;
            chosen = widthValue(widest, isStart);
        }
        boolean conflict = disagreesWithAnother(cluster, isStart, chosen);
        return new DateChoice(chosen, winner, conflict);
    }

    /** True when some dated source carries a value for this bound different from
     *  {@code chosen} — i.e. reconciliation had to pick a winner among conflicts. */
    private boolean disagreesWithAnother(List<Candidate> cluster, boolean isStart, String chosen) {
        if (chosen == null) return false;
        for (Candidate c : cluster) {
            String v = isStart ? c.start : c.end;
            if (v != null && !v.equals(chosen)) return true;
        }
        return false;
    }

    private String widthValue(Candidate c, boolean isStart) {
        return isStart ? c.start : c.end;
    }

    /** Is {@code candidate} wider than {@code current} for the given bound?
     *  Start: earlier is wider. End: later is wider. ISO strings compare lexically. */
    private boolean wider(String candidate, String current, boolean isStart) {
        if (current == null) return true;
        int cmp = candidate.compareTo(current);
        return isStart ? cmp < 0 : cmp > 0;
    }

    private String buildReasoning(List<Candidate> cluster, DateChoice start, DateChoice end,
                                  int totalYears) {
        StringBuilder sb = new StringBuilder();
        sb.append("Merged ").append(cluster.size())
                .append(cluster.size() == 1 ? " record" : " records")
                .append(" into one ").append(cluster.get(0).canonicalBranch).append(" enlistment. ");

        if (start.source != null) {
            sb.append("Start ").append(start.date).append(" from ")
                    .append(docLabel(start.source)).append(start.conflict ? " (conflict resolved by authority)" : "")
                    .append(". ");
        } else {
            sb.append("Start unknown. ");
        }
        if (end.source != null) {
            sb.append("End ").append(end.date).append(" from ")
                    .append(docLabel(end.source));
            if (end.conflict) {
                // Name the losing value for transparency.
                String losing = losingEnd(cluster, end.date);
                sb.append(" chosen over ");
                sb.append(losing != null ? losing : "a lower-authority record");
            }
            sb.append(". ");
        } else {
            sb.append("End unknown. ");
        }
        sb.append("Span ").append(totalYears)
                .append(totalYears == 1 ? " year." : " years.");
        return sb.toString();
    }

    /** Find an end value that lost to {@code chosen}, for the reasoning note. */
    private String losingEnd(List<Candidate> cluster, String chosen) {
        for (Candidate c : cluster) {
            if (c.end != null && !c.end.equals(chosen)) {
                return c.end + " from " + docLabel(c);
            }
        }
        return null;
    }

    private String docLabel(Candidate c) {
        if (c.docType == null || c.docType.isBlank()) return "an unclassified record";
        return c.docType;
    }

    // -------------------------------------------------------------------------
    // Static helpers (branch normalization, authority, spans) — package-visible
    // so tests can assert them directly.
    // -------------------------------------------------------------------------

    /**
     * Canonicalize a branch string via the synonym map (case-insensitive, trimmed).
     * Unknown ⇒ title-cased original. Null/blank ⇒ {@code "Unknown"} sentinel so
     * undated branch-only rows still cluster together rather than each becoming
     * their own conclusion.
     */
    static String canonicalBranch(String raw) {
        if (raw == null || raw.strip().isEmpty()) return "Unknown";
        String key = raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String canon = BRANCH_CANON.get(key);
        if (canon != null) return canon;
        return titleCase(raw.strip());
    }

    private static String titleCase(String s) {
        String[] words = s.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    /**
     * Map an EvidenceItem classification string → authority rank (heuristic,
     * contains-based). Manual/self-statement is lowest; a classified-but-unrecognized
     * doc is above manual but below any recognized document type.
     */
    static int authorityFor(String docType) {
        if (docType == null || docType.isBlank()) return RANK_UNKNOWN;
        String d = docType.toLowerCase(Locale.ROOT);
        if (DOCTYPE_MANUAL.equals(d) || d.contains("self-statement") || d.contains("self statement")
                || d.contains("statement in support") || d.contains("manual")) {
            return RANK_MANUAL;
        }
        if (d.contains("dd-214") || d.contains("dd214") || d.contains("dd 214")
                || d.contains("separation") || d.contains("discharge")) {
            return RANK_DD214;
        }
        if (d.contains("ngb-22") || d.contains("ngb22") || d.contains("ngb 22")
                || d.contains("national guard")) {
            return RANK_NGB22;
        }
        if (d.contains("personnel") || d.contains("treatment") || d.contains("service record")
                || d.contains("str") || d.contains("military record")) {
            return RANK_SERVICE_REC;
        }
        if (d.contains("order")) {
            return RANK_ORDERS;
        }
        return RANK_UNKNOWN;
    }

    /** Whole-year span endYear − startYear (min 0); 0 when either bound unknown. */
    static int spanYears(String start, String end) {
        Integer sy = year(start);
        Integer ey = year(end);
        if (sy == null || ey == null) return 0;
        return Math.max(0, ey - sy);
    }

    private static Integer year(String iso) {
        if (iso == null || iso.length() < 4) return null;
        try {
            return Integer.parseInt(iso.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int min(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.min(a, b);
    }

    private static int max(Integer a, Integer b) {
        if (a == null) return b;
        if (b == null) return a;
        return Math.max(a, b);
    }

    /** Gap in days between two dated intervals; ≤0 means overlap/touch. */
    private long intervalGapDays(String aStart, String aEnd, String bStart, String bEnd) {
        // Compare on years (records are YYYY-MM-DD; year granularity is enough for
        // the ~1yr adjacency window and avoids date-parse dependencies).
        int aLo = year(aStart), aHi = year(aEnd);
        int bLo = year(bStart), bHi = year(bEnd);
        if (aHi < bLo) return (long) (bLo - aHi) * 366;
        if (bHi < aLo) return (long) (aLo - bHi) * 366;
        return 0; // overlap
    }

    // -------------------------------------------------------------------------
    // Internal value holders
    // -------------------------------------------------------------------------

    /** A raw period pre-resolved with canonical branch + authority for clustering. */
    private static final class Candidate {
        final Long evidenceId;
        final String docType;
        final int authorityRank;
        final String canonicalBranch;
        final String component;
        final String rawBranch;
        final String start;
        final String end;
        final String rawMos;
        final String rawRank;
        final String source;

        private Candidate(Long evidenceId, String docType, int authorityRank, String canonicalBranch,
                          String component, String rawBranch, String start, String end,
                          String rawMos, String rawRank, String source) {
            this.evidenceId = evidenceId;
            this.docType = docType;
            this.authorityRank = authorityRank;
            this.canonicalBranch = canonicalBranch;
            this.component = component;
            this.rawBranch = rawBranch;
            this.start = start;
            this.end = end;
            this.rawMos = rawMos;
            this.rawRank = rawRank;
            this.source = source;
        }

        static Candidate of(RawPeriod rp, Map<Long, String> classes) {
            ServicePeriodDto p = rp.period();
            boolean manual = ServicePeriodDto.SOURCE_MANUAL.equals(p.getSource());
            String docType = manual ? DOCTYPE_MANUAL
                    : (rp.evidenceId() != null ? classes.get(rp.evidenceId()) : null);
            int rank = manual ? RANK_MANUAL : authorityFor(docType);
            return new Candidate(rp.evidenceId(), docType, rank,
                    canonicalBranch(p.getBranch()), p.getComponent(), p.getBranch(),
                    p.getStartDate(), p.getEndDate(), p.getMos(), p.getRank(), p.getSource());
        }
    }

    /** A resolved date bound with its winning source + whether it beat a conflict. */
    private record DateChoice(String date, Candidate source, boolean conflict) {
    }
}
