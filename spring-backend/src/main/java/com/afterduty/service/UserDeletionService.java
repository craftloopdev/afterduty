package com.afterduty.service;

import com.google.firebase.auth.FirebaseAuth;
import com.afterduty.model.EvidenceItem;
import com.afterduty.model.User;
import com.afterduty.repository.ClaimRepository;
import com.afterduty.repository.EvidenceItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Complete, Apple-Guideline-5.1.1(v)-compliant deletion of a user account and
 * <em>all</em> of their personal data.
 *
 * <p>The user-data graph spans many tables whose FKs ({@code REFERENCES
 * users(id)} / {@code claims(id)}) have <strong>no</strong>
 * {@code ON DELETE CASCADE} (schema is Hibernate {@code ddl-auto=update}).
 * Relying on JPA entity cascade alone is insufficient — it doesn't reach the
 * non-cascaded tables and it can leave cross-references (e.g. atoms this user
 * authored on <em>other</em> users' shared claims, chat threads where this user
 * is the viewer) that would block the {@code users} row delete with a FK
 * violation. So we delete explicitly, in FK-safe order (children before
 * parents), with idempotent bulk JPQL. Running it twice — or against a user who
 * has no rows — is a no-op and never throws.
 *
 * <p>External side effects (Stripe, GCS, Firebase Auth) are best-effort and run
 * <em>around</em> the DB transaction: each is wrapped so a single failure logs
 * and is swallowed rather than aborting the account deletion. The one thing we
 * never swallow is the DB deletion itself — that must complete for the request
 * to report success.
 */
@Service
public class UserDeletionService {

    private static final Logger log = LoggerFactory.getLogger(UserDeletionService.class);

    private final EvidenceItemRepository evidenceItemRepository;
    private final ClaimRepository claimRepository;
    private final StripeService stripeService;
    private final DocumentStorageService documentStorageService;
    private final TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager em;

    public UserDeletionService(EvidenceItemRepository evidenceItemRepository,
                               ClaimRepository claimRepository,
                               StripeService stripeService,
                               DocumentStorageService documentStorageService,
                               PlatformTransactionManager transactionManager) {
        this.evidenceItemRepository = evidenceItemRepository;
        this.claimRepository = claimRepository;
        this.stripeService = stripeService;
        this.documentStorageService = documentStorageService;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Deletes the given user and every row of their personal data.
     *
     * <p>Order of operations:
     * <ol>
     *   <li>Cancel Stripe subscriptions (best-effort, before we lose the
     *       {@code stripeCustomerId}).</li>
     *   <li>Delete the user's uploaded GCS documents (best-effort, before the
     *       {@code evidence_items} rows that hold the storage paths are gone).</li>
     *   <li>Delete all DB rows in FK-safe order in a single transaction.</li>
     *   <li>Delete the Firebase Auth user (best-effort, after the DB row is gone
     *       so they cannot silently reappear via getOrCreate on next request).</li>
     * </ol>
     */
    public void deleteAccount(User user) {
        Long userId = user.getId();
        log.info("Account deletion requested for user {}", userId);

        // 1. Stripe — cancel recurring billing before the customer is orphaned.
        try {
            int cancelled = stripeService.cancelActiveSubscriptions(user);
            if (cancelled > 0) {
                log.info("Cancelled {} Stripe subscription(s) for user {}", cancelled, userId);
            }
        } catch (RuntimeException e) {
            log.warn("Stripe cancellation failed for user {} — continuing with deletion: {}",
                    userId, e.getMessage());
        }

        // 2. GCS — delete uploaded evidence files while we still have their paths.
        deleteGcsDocuments(userId);

        // 3. DB — ordered, idempotent, in ONE transaction. Wrapped in an explicit
        //    TransactionTemplate rather than relying on @Transactional: this is an
        //    internal (self-)invocation, so an @Transactional annotation on
        //    deleteAllUserData would be bypassed by the Spring proxy and the bulk
        //    JPQL would run with no active transaction (TransactionRequiredException
        //    on the first delete). The template also keeps the DB transaction scoped
        //    to just this phase — the external calls above/below run outside it.
        txTemplate.executeWithoutResult(status -> deleteAllUserData(userId));

        // 4. Firebase Auth — remove the credential so the account can't reappear.
        deleteFirebaseUser(user);

        log.info("Account deletion complete for user {}", userId);
    }

    /**
     * Best-effort deletion of every GCS object backing this user's evidence.
     * Reads each evidence item's {@code gcs_path} before the rows are deleted.
     * A failure to delete any single object is logged and ignored.
     */
    private void deleteGcsDocuments(Long userId) {
        List<Long> claimIds = userClaimIds(userId);
        if (claimIds.isEmpty()) {
            return;
        }
        for (Long claimId : claimIds) {
            for (EvidenceItem item : evidenceItemRepository.findByClaimId(claimId)) {
                String path = item.getGcsPath();
                if (path == null || path.isBlank()) {
                    continue;
                }
                try {
                    documentStorageService.deleteFromGcs(path);
                } catch (RuntimeException e) {
                    log.warn("Failed to delete GCS object {} for user {} — continuing: {}",
                            path, userId, e.getMessage());
                }
            }
        }
    }

    /**
     * Deletes all DB rows owned by or referencing this user, in FK-safe order
     * (children before parents), then the {@code users} row itself.
     *
     * <p>Implemented as explicit bulk JPQL so the order is obvious and the whole
     * thing is idempotent — every statement is a no-op when there's nothing to
     * delete, so re-running (or running against an already-deleted user) is
     * safe and never throws on a FK constraint.
     *
     * <p><strong>Must run inside an active transaction.</strong> {@link
     * #deleteAccount(User)} provides one via {@code txTemplate}; direct callers
     * (e.g. tests) must supply their own. Not annotated {@code @Transactional}
     * because its only production caller invokes it internally, where the proxy
     * (and thus the annotation) would be bypassed.
     */
    public void deleteAllUserData(Long userId) {
        List<Long> claimIds = userClaimIds(userId);

        // Bulk JPQL operates straight on the DB and bypasses the persistence
        // context; clear it first so no stale managed entities linger, and the
        // empty-IN guard below avoids invalid "IN ()" SQL when the user has no
        // claims.
        boolean hasClaims = !claimIds.isEmpty();

        // 1. atoms — for the user's claims AND any this user authored on other
        //    users' (shared) claims. The latter would block the users-row delete.
        if (hasClaims) {
            em.createQuery("delete from Atom a where a.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
        }
        em.createQuery("delete from Atom a where a.creatorUserId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 2. intake_messages — for the user's claims.
        if (hasClaims) {
            em.createQuery("delete from IntakeMessage m where m.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
        }

        // 3. medical_events — for the user's claims.
        if (hasClaims) {
            em.createQuery("delete from MedicalEvent e where e.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
        }

        // 4. pipeline_metrics / claim_pipeline_jobs / ai_call_logs — by claim;
        //    ai_call_logs also carries a direct user_id.
        if (hasClaims) {
            em.createQuery("delete from PipelineMetrics p where p.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
            em.createQuery("delete from ClaimPipelineJob j where j.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
            em.createQuery("delete from AiCallLog l where l.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
        }
        em.createQuery("delete from AiCallLog l where l.userId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 5. chat_threads — viewer is this user, or thread is on this user's
        //    claims. Done after atoms + intake_messages (which reference
        //    thread_id) are gone.
        if (hasClaims) {
            em.createQuery("delete from ChatThread t where t.viewerUserId = :userId or t.claimId in :claimIds")
                    .setParameter("userId", userId).setParameter("claimIds", claimIds).executeUpdate();
        } else {
            em.createQuery("delete from ChatThread t where t.viewerUserId = :userId")
                    .setParameter("userId", userId).executeUpdate();
        }

        // 6. evidence_items + identified_conditions — by claim.
        if (hasClaims) {
            em.createQuery("delete from EvidenceItem ev where ev.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
            em.createQuery("delete from IdentifiedCondition c where c.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
        }

        // 7. claim_scenarios — by user.
        em.createQuery("delete from ClaimScenario s where s.userId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 8. shares — owner is this user, viewer is this user, or share targets
        //    one of this user's claims.
        if (hasClaims) {
            em.createQuery("delete from Share s where s.ownerUserId = :userId "
                            + "or s.viewerUserId = :userId or s.claimId in :claimIds")
                    .setParameter("userId", userId).setParameter("claimIds", claimIds).executeUpdate();
        } else {
            em.createQuery("delete from Share s where s.ownerUserId = :userId or s.viewerUserId = :userId")
                    .setParameter("userId", userId).executeUpdate();
        }

        // 9. notifications — by user, or referencing one of this user's claims.
        if (hasClaims) {
            em.createQuery("delete from Notification n where n.userId = :userId or n.claimId in :claimIds")
                    .setParameter("userId", userId).setParameter("claimIds", claimIds).executeUpdate();
        } else {
            em.createQuery("delete from Notification n where n.userId = :userId")
                    .setParameter("userId", userId).executeUpdate();
        }

        // 10. llm_jobs — by user AND by claim. A job's request/response payloads
        //     contain the user's claim data, so leaving any behind is an
        //     incomplete wipe (Apple 5.1.1(v)). A job can be tagged to one of
        //     this user's claims while carrying a different/absent user_id (e.g.
        //     a run triggered on a shared claim), which the user_id pass alone
        //     would miss — so delete by claimId too, mirroring ai_call_logs.
        if (hasClaims) {
            em.createQuery("delete from LlmJob j where j.claimId in :claimIds")
                    .setParameter("claimIds", claimIds).executeUpdate();
        }
        em.createQuery("delete from LlmJob j where j.userId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 11. service_profiles — by user.
        em.createQuery("delete from ServiceProfile p where p.userId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 11b. webauthn_credentials + webauthn_challenges — by user. A passkey is
        //     an authentication credential for THIS account; leaving it behind is
        //     both an incomplete wipe (Apple 5.1.1(v)/GDPR) AND a security hole:
        //     credential_id carries a global UNIQUE index and the assert path
        //     resolves purely by credential_id + the deterministic user_handle
        //     (both derived from the numeric userId). An orphaned row would keep
        //     a globally-unique credential id claimed forever (blocking re-enroll
        //     with a 409) and — on any user-id reuse/restore — could resolve and
        //     authenticate a stale credential onto the new account. Delete both
        //     the enrolled credentials and any in-flight challenges.
        em.createQuery("delete from WebAuthnCredential w where w.userId = :userId")
                .setParameter("userId", userId).executeUpdate();
        em.createQuery("delete from WebAuthnChallenge w where w.userId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 11c. device_credentials — by user. Same orphan-credential class as the
        //     passkeys above: each row holds a biometric device-login secret HASH
        //     bound to this userId, and /device/exchange resolves by row id +
        //     constant-time secret hash to mint a session for row.userId. Leaving
        //     rows behind is an incomplete wipe (Apple 5.1.1(v)/GDPR) and — on any
        //     user-id reuse — a stale device credential could authenticate onto
        //     the new account. Hard-delete at account-deletion time.
        em.createQuery("delete from DeviceCredential d where d.userId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 12. claims — by user.
        em.createQuery("delete from Claim c where c.userId = :userId")
                .setParameter("userId", userId).executeUpdate();

        // 13. users — the row itself.
        em.createQuery("delete from User u where u.id = :userId")
                .setParameter("userId", userId).executeUpdate();

        em.flush();
        em.clear();
        log.info("Deleted all DB rows for user {} (claims={})", userId, claimIds.size());
    }

    /**
     * Best-effort Firebase Auth deletion. Skipped entirely for dev-mode users
     * (firebaseUid {@code null} or starting with {@code "dev_"}), since those
     * are local placeholders with no real Firebase credential. Any failure
     * (already-deleted user, Firebase unavailable) is logged, not propagated.
     */
    private void deleteFirebaseUser(User user) {
        String uid = user.getFirebaseUid();
        if (uid == null || uid.startsWith("dev_")) {
            log.info("Skipping Firebase delete for user {} (uid={})", user.getId(), uid);
            return;
        }
        try {
            FirebaseAuth.getInstance().deleteUser(uid);
            log.info("Deleted Firebase Auth user {} for app user {}", uid, user.getId());
        } catch (Exception e) {
            log.warn("Firebase Auth delete failed for uid {} (user {}) — account already removed from DB: {}",
                    uid, user.getId(), e.getMessage());
        }
    }

    private List<Long> userClaimIds(Long userId) {
        return claimRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(c -> c.getId())
                .toList();
    }
}
