package com.afterduty.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String name;

    /** User-chosen display name ("What should we call you?"). Set via
     *  PATCH /api/auth/me; trimmed, 1..60 chars. Null until the user picks one.
     *  Kept in lockstep with V20260703_1__preferred_name.sql for ddl-auto. */
    @Column(name = "preferred_name", length = 60)
    private String preferredName;

    @Column(name = "firebase_uid", unique = true)
    private String firebaseUid;

    @Column(nullable = false)
    private String role = "veteran";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // Stripe-driven subscription state. expiresAt is the source of truth for
    // "is this user paying right now"; stripeCustomerId is set on first
    // checkout and reused for renewals; lastStripeSessionId is informational.
    @Column(name = "subscription_expires_at")
    private Instant subscriptionExpiresAt;

    @Column(name = "stripe_customer_id")
    private String stripeCustomerId;

    @Column(name = "last_stripe_session_id")
    private String lastStripeSessionId;

    /** Which billing rail set the current subscriptionExpiresAt:
     *  {@code stripe} (web), {@code apple} (App Store IAP via RevenueCat),
     *  {@code google} (Play Billing via RevenueCat). Null on legacy rows / never paid.
     *  Used to route "Manage subscription" to the right portal — Apple requires
     *  native subscribers be sent to the App Store, not Stripe. */
    @Column(name = "subscription_source")
    private String subscriptionSource;

    /** When the user first joined a paid plan. Used by UsageService to
     *  decide which monthly window to roll into when computing the cap. */
    @Column(name = "subscription_started_at")
    private Instant subscriptionStartedAt;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private ServiceProfile serviceProfile;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Claim> claims = new ArrayList<>();

    public User() {
    }

    public User(Long id, String email, String name, String firebaseUid, String role, Instant createdAt,
                Instant subscriptionStartedAt, Instant subscriptionExpiresAt,
                ServiceProfile serviceProfile, List<Claim> claims) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.firebaseUid = firebaseUid;
        this.role = role;
        this.createdAt = createdAt;
        this.subscriptionStartedAt = subscriptionStartedAt;
        this.subscriptionExpiresAt = subscriptionExpiresAt;
        this.serviceProfile = serviceProfile;
        this.claims = claims;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    /**
     * Phone-OTP signups have no email, so FirebaseAuthService stores a
     * synthetic {@code "<uid>@firebase.local"} placeholder to satisfy the
     * column. Anything leaving the system (API responses, Stripe, email
     * senders) must treat those as ABSENT — this is the single source of that
     * rule (AuthController.publicEmail and StripeService both ride it).
     */
    public static final String SYNTHETIC_EMAIL_SUFFIX = "@firebase.local";

    /** The stored email, or null when it's a synthetic placeholder. */
    public String getRealEmail() {
        if (email == null) return null;
        return email.toLowerCase(java.util.Locale.ROOT).endsWith(SYNTHETIC_EMAIL_SUFFIX) ? null : email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPreferredName() {
        return preferredName;
    }

    public void setPreferredName(String preferredName) {
        this.preferredName = preferredName;
    }

    public String getFirebaseUid() {
        return firebaseUid;
    }

    public void setFirebaseUid(String firebaseUid) {
        this.firebaseUid = firebaseUid;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSubscriptionExpiresAt() {
        return subscriptionExpiresAt;
    }

    public void setSubscriptionExpiresAt(Instant subscriptionExpiresAt) {
        this.subscriptionExpiresAt = subscriptionExpiresAt;
    }

    public String getStripeCustomerId() {
        return stripeCustomerId;
    }

    public void setStripeCustomerId(String stripeCustomerId) {
        this.stripeCustomerId = stripeCustomerId;
    }

    public String getLastStripeSessionId() {
        return lastStripeSessionId;
    }

    public void setLastStripeSessionId(String lastStripeSessionId) {
        this.lastStripeSessionId = lastStripeSessionId;
    }

    public String getSubscriptionSource() {
        return subscriptionSource;
    }

    public void setSubscriptionSource(String subscriptionSource) {
        this.subscriptionSource = subscriptionSource;
    }

    public boolean hasActiveSubscription() {
        return subscriptionExpiresAt != null && subscriptionExpiresAt.isAfter(Instant.now());
    }

    public Instant getSubscriptionStartedAt() {
        return subscriptionStartedAt;
    }

    public void setSubscriptionStartedAt(Instant subscriptionStartedAt) {
        this.subscriptionStartedAt = subscriptionStartedAt;
    }

    public ServiceProfile getServiceProfile() {
        return serviceProfile;
    }

    public void setServiceProfile(ServiceProfile serviceProfile) {
        this.serviceProfile = serviceProfile;
    }

    public List<Claim> getClaims() {
        return claims;
    }

    public void setClaims(List<Claim> claims) {
        this.claims = claims;
    }

    /**
     * JPA-convention equality: id is the source of truth. Comparing by
     * arbitrary fields (subscriptionStartedAt etc.) creates inconsistency
     * when those fields are null pre-save or change between fetches.
     * Two persisted Users with the same id are the same User.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return id != null && Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "User(" +
                "id=" + id +
                ", email=" + email +
                ", name=" + name +
                ", firebaseUid=" + firebaseUid +
                ", role=" + role +
                ", createdAt=" + createdAt +
                ", subscriptionStartedAt=" + subscriptionStartedAt +
                ')';
    }

    public static UserBuilder builder() {
        return new UserBuilder();
    }

    public static class UserBuilder {
        private Long id;
        private String email;
        private String name;
        private String preferredName;
        private String firebaseUid;
        private String role = "veteran";
        private Instant createdAt = Instant.now();
        private Instant subscriptionStartedAt;
        private Instant subscriptionExpiresAt;
        private ServiceProfile serviceProfile;
        private List<Claim> claims = new ArrayList<>();

        UserBuilder() {
        }

        public UserBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public UserBuilder email(String email) {
            this.email = email;
            return this;
        }

        public UserBuilder name(String name) {
            this.name = name;
            return this;
        }

        public UserBuilder preferredName(String preferredName) {
            this.preferredName = preferredName;
            return this;
        }

        public UserBuilder firebaseUid(String firebaseUid) {
            this.firebaseUid = firebaseUid;
            return this;
        }

        public UserBuilder role(String role) {
            this.role = role;
            return this;
        }

        public UserBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public UserBuilder subscriptionStartedAt(Instant subscriptionStartedAt) {
            this.subscriptionStartedAt = subscriptionStartedAt;
            return this;
        }

        public UserBuilder subscriptionExpiresAt(Instant subscriptionExpiresAt) {
            this.subscriptionExpiresAt = subscriptionExpiresAt;
            return this;
        }

        public UserBuilder serviceProfile(ServiceProfile serviceProfile) {
            this.serviceProfile = serviceProfile;
            return this;
        }

        public UserBuilder claims(List<Claim> claims) {
            this.claims = claims;
            return this;
        }

        public User build() {
            User user = new User(id, email, name, firebaseUid, role, createdAt, subscriptionStartedAt,
                    subscriptionExpiresAt, serviceProfile, claims);
            user.setPreferredName(preferredName);
            return user;
        }
    }
}
