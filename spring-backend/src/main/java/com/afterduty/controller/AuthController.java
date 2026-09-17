package com.afterduty.controller;

import com.afterduty.config.SecurityConfig;
import com.afterduty.dto.*;
import com.afterduty.model.Claim;
import com.afterduty.model.ServiceHistoryOverride;
import com.afterduty.model.ServiceProfile;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.ServiceHistoryOverrideRepository;
import com.afterduty.repository.ServiceProfileRepository;
import com.afterduty.repository.UserRepository;
import com.afterduty.model.AuthAuditLog;
import com.afterduty.service.AuthAuditService;
import com.afterduty.service.ServicePeriodDeriver;
import com.afterduty.service.ShareService;
import com.afterduty.service.UserDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Synthetic email domain minted at account creation for phone-OTP / no-email
     *  Firebase identities (see FirebaseAuthService). Stored, never wire-visible. */
    private static final String SYNTHETIC_EMAIL_SUFFIX = com.afterduty.model.User.SYNTHETIC_EMAIL_SUFFIX;

    private static final int PREFERRED_NAME_MAX = 60;

    private final ServiceProfileRepository serviceProfileRepository;
    private final ClaimRepository claimRepository;
    private final UserRepository userRepository;
    private final IntakeController intakeController;
    private final ShareService shareService;
    private final UserDeletionService userDeletionService;
    private final ServicePeriodDeriver servicePeriodDeriver;
    private final ServiceHistoryOverrideRepository overrideRepository;
    private final AuthAuditService auditService;

    public AuthController(ServiceProfileRepository serviceProfileRepository,
                          ClaimRepository claimRepository,
                          UserRepository userRepository,
                          IntakeController intakeController,
                          ShareService shareService,
                          UserDeletionService userDeletionService,
                          ServicePeriodDeriver servicePeriodDeriver,
                          ServiceHistoryOverrideRepository overrideRepository,
                          AuthAuditService auditService) {
        this.serviceProfileRepository = serviceProfileRepository;
        this.claimRepository = claimRepository;
        this.userRepository = userRepository;
        this.intakeController = intakeController;
        this.shareService = shareService;
        this.userDeletionService = userDeletionService;
        this.servicePeriodDeriver = servicePeriodDeriver;
        this.overrideRepository = overrideRepository;
        this.auditService = auditService;
    }

    @GetMapping("/me")
    public UserResponse getMe(HttpServletRequest request) {
        User user = getCurrentUser(request);
        boolean hasProfile = serviceProfileRepository.findByUserId(user.getId()).isPresent();

        // Auto-create claim if user has none, and build claim response.
        // X-View-As is intentionally ignored here — getMe always returns the user's OWN claim
        // plus the list of profiles they can access (own + accepted shares).
        Claim activeClaim = intakeController.getOrCreateActiveClaim(user);
        ClaimResponse claimResponse = intakeController.toClaimResponse(activeClaim);

        // /me is the frontend's bootstrap call; a failure here blocks ALL
        // login. Degrade gracefully if the shares lookup itself fails — return
        // an empty list rather than 500 the whole response. Real failures still
        // hit the log so we can diagnose without breaking sign-in for users.
        List<ProfileSummaryDto> sharedProfiles;
        try {
            sharedProfiles = shareService.listProfiles(user).stream()
                    .filter(p -> !p.isOwn())
                    .toList();
        } catch (RuntimeException e) {
            log.error("getMe: listProfiles failed for user={}; degrading to empty sharedProfiles",
                    user.getId(), e);
            sharedProfiles = Collections.emptyList();
        }

        return UserResponse.builder()
                .id(user.getId())
                .email(publicEmail(user.getEmail()))
                .name(user.getName())
                .preferredName(user.getPreferredName())
                .role(user.getRole())
                .hasProfile(hasProfile)
                .activeClaim(claimResponse)
                .sharedProfiles(sharedProfiles)
                .build();
    }

    /**
     * Wire-visible email: synthetic {@code "<uid>@firebase.local"} placeholders
     * (any case) never reach a client — the key is omitted (UserResponse marks
     * the field NON_NULL) so the UI can offer "Add an email" via the OTP attach
     * flow. The STORED value is untouched; once the veteran attaches a real
     * email, EmailCodeAuthController mirrors it onto the users row and it flows
     * through here again.
     */
    static String publicEmail(String storedEmail) {
        if (storedEmail == null) return null;
        return storedEmail.toLowerCase(Locale.ROOT).endsWith(SYNTHETIC_EMAIL_SUFFIX)
                ? null : storedEmail;
    }

    /**
     * "What should we call you?" — sets the account's preferred display name.
     *
     * <p>Body: {@code {"preferredName": string}} — trimmed, 1..60 chars (400
     * outside those bounds or when missing/not a string). Returns
     * {@code {"ok":true, "preferredName": "<trimmed>"}}.
     *
     * <p>X-View-As is intentionally NOT honored — this is an account-level
     * mutation on the Bearer principal only; a VSO viewing a shared claim must
     * never rename the veteran.
     */
    @PatchMapping("/me")
    public Map<String, Object> patchMe(@RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        User user = getCurrentUser(request);
        Object raw = body != null ? body.get("preferredName") : null;
        if (!(raw instanceof String s)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "preferredName is required");
        }
        String preferredName = s.trim();
        if (preferredName.isEmpty() || preferredName.length() > PREFERRED_NAME_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "preferredName must be 1-" + PREFERRED_NAME_MAX + " characters");
        }
        user.setPreferredName(preferredName);
        userRepository.save(user);
        return Map.of("ok", true, "preferredName", preferredName);
    }

    @PostMapping("/profile")
    public ServiceProfileResponse createOrUpdateProfile(@RequestBody ServiceProfileRequest req,
                                                         HttpServletRequest request) {
        User user = getCurrentUser(request);
        ServiceProfile profile = serviceProfileRepository.findByUserId(user.getId()).orElse(null);

        if (profile != null) {
            if (req.getBranch() != null) profile.setBranch(req.getBranch());
            if (req.getServiceStart() != null) profile.setServiceStart(req.getServiceStart());
            if (req.getServiceEnd() != null) profile.setServiceEnd(req.getServiceEnd());
            if (req.getDutyStations() != null) profile.setDutyStations(req.getDutyStations());
            if (req.getDeployments() != null) profile.setDeployments(req.getDeployments());
            if (req.getMos() != null) profile.setMos(req.getMos());
            if (req.getExposureRisks() != null) profile.setExposureRisks(req.getExposureRisks());
        } else {
            profile = ServiceProfile.builder()
                    .user(user)
                    .branch(req.getBranch())
                    .serviceStart(req.getServiceStart())
                    .serviceEnd(req.getServiceEnd())
                    .dutyStations(req.getDutyStations() != null ? req.getDutyStations() : Collections.emptyList())
                    .deployments(req.getDeployments() != null ? req.getDeployments() : Collections.emptyList())
                    .mos(req.getMos())
                    .exposureRisks(req.getExposureRisks() != null ? req.getExposureRisks() : Collections.emptyList())
                    .build();
        }

        profile = serviceProfileRepository.save(profile);
        return toResponse(profile, servicePeriodDeriver.deriveForUser(user));
    }

    /**
     * The veteran's service profile + derived {@code servicePeriods[]}.
     *
     * <p>Always 200 now (previously 204 when no manual ServiceProfile row
     * existed): {@code servicePeriods} is deterministically derived from
     * already-extracted document atoms even when the veteran never filled the
     * manual form, so an empty manual row must not hide it. Without a manual
     * row the legacy fields are null and {@code servicePeriods} is present
     * (possibly empty).
     */
    @GetMapping("/profile")
    public ResponseEntity<ServiceProfileResponse> getProfile(HttpServletRequest request) {
        User user = getCurrentUser(request);
        List<ServicePeriodDto> servicePeriods = servicePeriodDeriver.deriveForUser(user);
        ServiceProfileResponse response = serviceProfileRepository.findByUserId(user.getId())
                .map(p -> toResponse(p, servicePeriods))
                .orElseGet(() -> ServiceProfileResponse.builder()
                        .userId(user.getId())
                        .dutyStations(Collections.emptyList())
                        .deployments(Collections.emptyList())
                        .exposureRisks(Collections.emptyList())
                        .servicePeriods(servicePeriods)
                        .build());
        return ResponseEntity.ok(response);
    }

    private ServiceProfileResponse toResponse(ServiceProfile p, List<ServicePeriodDto> servicePeriods) {
        return ServiceProfileResponse.builder()
                .id(p.getId())
                .userId(p.getUser().getId())
                .branch(p.getBranch())
                .serviceStart(p.getServiceStart())
                .serviceEnd(p.getServiceEnd())
                .dutyStations(p.getDutyStations() != null ? p.getDutyStations() : Collections.emptyList())
                .deployments(p.getDeployments() != null ? p.getDeployments() : Collections.emptyList())
                .mos(p.getMos())
                .exposureRisks(p.getExposureRisks() != null ? p.getExposureRisks() : Collections.emptyList())
                .servicePeriods(servicePeriods)
                .build();
    }

    private static final Set<String> VALID_COMPONENTS = Set.of(
            ServicePeriodDto.COMPONENT_ACTIVE,
            ServicePeriodDto.COMPONENT_GUARD,
            ServicePeriodDto.COMPONENT_RESERVE);

    private static final java.util.regex.Pattern ISO_DATE =
            java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private static final int OVERRIDE_FIELD_MAX = 120;

    /**
     * Veteran override for ONE reconciled service-history conclusion (Service
     * History P3 Part A). Upsert the correction for {@code (currentUser,
     * clusterKey)}: only the SET fields override the reconciled values (all
     * nullable), and the reconciler re-applies them at TOP authority ("Corrected by
     * you") keyed by the STABLE cluster key so the correction survives re-derivation.
     *
     * <p>ACCOUNT-LEVEL + OWNER-ONLY: the row is always written for the resolved
     * {@code getCurrentUser} principal — {@code clusterKey} is data selecting WHICH
     * of the owner's conclusions to correct, never WHOSE. X-View-As is not honored
     * (this path takes the Bearer principal only; the BFF strips viewer headers from
     * mutations too), so a VSO viewing a shared claim can never correct the veteran's
     * history.
     *
     * <p>Returns the freshly re-derived {@code servicePeriods[]} (with the override
     * applied) so the client renders the corrected state without a second round-trip.
     * 400 when {@code clusterKey} is missing/blank, no field is set, or a field is
     * malformed (bad component / non-ISO date).
     */
    @PostMapping("/service-history/override")
    public ResponseEntity<Map<String, Object>> upsertServiceHistoryOverride(
            @RequestBody(required = false) ServiceHistoryOverrideRequest req,
            HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (req == null || req.getClusterKey() == null || req.getClusterKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clusterKey is required");
        }
        String branch = trimToNull(req.getBranch());
        String component = trimToNull(req.getComponent());
        String startDate = trimToNull(req.getStartDate());
        String endDate = trimToNull(req.getEndDate());
        String mos = trimToNull(req.getMos());
        String rank = trimToNull(req.getRank());

        if (component != null && !VALID_COMPONENTS.contains(component)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "component must be active, guard, or reserve");
        }
        validateIsoOrThrow(startDate, "startDate");
        validateIsoOrThrow(endDate, "endDate");
        validateLenOrThrow(branch, "branch");
        validateLenOrThrow(mos, "mos");
        validateLenOrThrow(rank, "rank");

        if (branch == null && component == null && startDate == null && endDate == null
                && mos == null && rank == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at least one field must be set to override");
        }

        String clusterKey = req.getClusterKey().strip();
        // Upsert scoped to the OWNER — never another account's row.
        ServiceHistoryOverride override = overrideRepository
                .findByUserIdAndClusterKey(user.getId(), clusterKey)
                .orElseGet(ServiceHistoryOverride::new);
        boolean isNew = override.getId() == null;
        if (isNew) {
            override.setUserId(user.getId());
            override.setClusterKey(clusterKey);
            override.setCreatedAt(Instant.now());
        }
        override.setBranch(branch);
        override.setComponent(component);
        override.setStartDate(startDate);
        override.setEndDate(endDate);
        override.setMos(mos);
        override.setRank(rank);
        override.setUpdatedAt(Instant.now());
        overrideRepository.save(override);

        List<ServicePeriodDto> servicePeriods = servicePeriodDeriver.deriveForUser(user);
        return ResponseEntity.ok(Map.of("ok", true, "servicePeriods", servicePeriods));
    }

    /**
     * Clear the authenticated owner's override for one conclusion — the conclusion
     * reverts to the reconciled (document-derived) values on the next derive. Scoped
     * to {@code (currentUser, clusterKey)} so it can only ever remove the owner's
     * row. Idempotent (deleting a nonexistent override is a no-op 200). Returns the
     * re-derived {@code servicePeriods[]}.
     */
    @DeleteMapping("/service-history/override/{clusterKey}")
    public ResponseEntity<Map<String, Object>> deleteServiceHistoryOverride(
            @PathVariable String clusterKey, HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (clusterKey == null || clusterKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clusterKey is required");
        }
        overrideRepository.deleteByUserIdAndClusterKey(user.getId(), clusterKey.strip());
        List<ServicePeriodDto> servicePeriods = servicePeriodDeriver.deriveForUser(user);
        return ResponseEntity.ok(Map.of("ok", true, "servicePeriods", servicePeriods));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static void validateIsoOrThrow(String value, String field) {
        if (value != null && !ISO_DATE.matcher(value).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be an ISO date (YYYY-MM-DD)");
        }
    }

    private static void validateLenOrThrow(String value, String field) {
        if (value != null && value.length() > OVERRIDE_FIELD_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be " + OVERRIDE_FIELD_MAX + " characters or fewer");
        }
    }

    /**
     * Permanently and completely deletes the authenticated user's account and
     * all of their personal data — required by Apple Guideline 5.1.1(v) for
     * any app that supports account creation. Cancels Stripe billing, removes
     * uploaded GCS documents, deletes every related DB row in FK-safe order,
     * and removes the Firebase Auth credential. Idempotent.
     *
     * @return 204 No Content on success.
     */
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(HttpServletRequest request) {
        User user = getCurrentUser(request);
        log.info("DELETE /api/auth/account for user {}", user.getId());
        userDeletionService.deleteAccount(user);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * Best-effort sign-out audit hook (auth program P1.1). The actual session
     * teardown is client-side (web clears the httpOnly {@code cp_session} cookie;
     * native signs out of Firebase), but the audit log needs a SIGN_OUT event, so
     * clients call this before clearing local state. Idempotent, always 204 — a
     * failure here must never block the user from signing out.
     */
    @PostMapping("/sign-out")
    public ResponseEntity<Void> signOut(HttpServletRequest request) {
        User user = getCurrentUser(request);
        auditService.record(AuthAuditLog.EVENT_SIGN_OUT, null,
                AuthAuditLog.OUTCOME_SUCCESS, user.getId(), request);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    static User getCurrentUser(HttpServletRequest request) {
        User user = (User) request.getAttribute(SecurityConfig.USER_ATTRIBUTE);
        if (user == null) throw new SecurityException("Not authenticated");
        return user;
    }
}
