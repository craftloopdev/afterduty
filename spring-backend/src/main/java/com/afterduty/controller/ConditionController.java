package com.afterduty.controller;

import com.afterduty.model.Claim;
import com.afterduty.model.IdentifiedCondition;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ConditionRepository;
import com.afterduty.service.VaMathService;
import com.afterduty.service.VasrdDataService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static com.afterduty.controller.AuthController.getCurrentUser;

/**
 * Per-condition toggles + the VASRD/VA-math lookups the web app actually uses.
 *
 * <p>The saved-scenario CRUD (/api/scenarios create/read/update/delete) and
 * GET /api/vasrd/codes/{code} were removed 2026-08-02 with the Flutter frontend
 * — their only caller (docs/maintenance/dead-code-audit-2026-08-02.md). The
 * web ScenariosPanel computes what-ifs client-side over the stateless
 * {@code POST /api/scenarios/calculate} below; {@code ClaimScenario} rows and
 * their repository remain for {@code UserDeletionService} cleanup of
 * Flutter-era data. If "save this scenario" ships on web, resurrect the CRUD
 * (and its Mission-5b generation-redirect logic + tests) from git history
 * rather than rebuilding it.
 */
@RestController
public class ConditionController {

    private final ConditionRepository conditionRepository;
    private final ClaimRepository claimRepository;
    private final VaMathService vaMathService;
    private final VasrdDataService vasrdDataService;

    public ConditionController(ConditionRepository conditionRepository,
                               ClaimRepository claimRepository,
                               VaMathService vaMathService, VasrdDataService vasrdDataService) {
        this.conditionRepository = conditionRepository;
        this.claimRepository = claimRepository;
        this.vaMathService = vaMathService;
        this.vasrdDataService = vasrdDataService;
    }

    // --- Exclude / include a condition in the combined claim ---

    /**
     * "Don't include in my claim" toggle (owner-set, reversible) — body
     * {@code {"excluded": true|false}} → 204. The veteran is telling us this
     * condition is VALID but they aren't filing for it (a TDIU line, or anything
     * they'd rather leave out), so once excluded it DROPS OUT of the combined-rating
     * math ({@code RatingController} filters {@code excludedFromClaim}), which
     * cascades to the pay estimate. It STAYS in the conditions list (returned flagged
     * by {@code IntakeController}) so the web can show it in the "Not filing" section
     * with a one-tap re-include. This is DISTINCT from delete/suppress ("this is
     * wrong", {@code ConditionSuppression}) — a plain reversible boolean.
     *
     * <p>Owner-guarded exactly like the other per-condition mutations (VCP-AUTHZ-02):
     * the condition's claim must belong to the current user, or 403 — a viewer/
     * attacker can never toggle another veteran's condition. A missing condition is
     * 404; a stale/superseded (prior-generation) row is 404 too (its exclusion state
     * is meaningless — the active replacement carries the real flag). X-View-As is
     * intentionally NOT honored: this is an owner-only write.
     */
    @PostMapping("/api/claim/conditions/{conditionId}/exclude")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setExcludedFromClaim(@PathVariable Long conditionId,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest request) {
        User user = getCurrentUser(request);

        Object raw = body != null ? body.get("excluded") : null;
        if (!(raw instanceof Boolean excluded)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "excluded must be a boolean");
        }

        IdentifiedCondition cond = conditionRepository.findById(conditionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Condition not found"));

        // Owner guard: the condition's claim must belong to the caller (403 otherwise
        // — never leak whether the id exists on someone else's claim as a 404).
        Claim claim = claimRepository.findById(cond.getClaimId()).orElse(null);
        if (claim == null || !user.getId().equals(claim.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not_your_condition");
        }

        // A superseded prior-generation row is never the live target — its flag would
        // be ignored by the rating math; treat as not found.
        if (cond.getSupersededBy() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Condition not found");
        }

        cond.setExcludedFromClaim(excluded);
        conditionRepository.save(cond);
    }

    // --- VASRD ---

    @GetMapping("/api/vasrd/search")
    public List<Map<String, Object>> searchVasrd(@RequestParam(defaultValue = "") String q,
                                                  HttpServletRequest request) {
        getCurrentUser(request);
        if (q.length() < 2) return List.of();
        return vasrdDataService.search(q);
    }

    // --- Stateless what-if math (the web ScenariosPanel's engine) ---

    @PostMapping("/api/scenarios/calculate")
    public Map<String, Object> calculateRating(@RequestBody List<Integer> ratings) {
        return vaMathService.calculateCombinedRating(ratings);
    }
}
