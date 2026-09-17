package com.afterduty.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class UserResponse {
    private Long id;
    /** Real email only — synthetic "<uid>@firebase.local" placeholders are
     *  nulled at the response layer (AuthController.publicEmail) and the key
     *  is omitted from the wire entirely so clients can offer "Add an email".
     *  (The app-wide jackson default is already non_null; pinned explicitly
     *  here because the wire contract depends on the key being absent.) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String email;
    private String name;
    /** User-chosen display name; null until set via PATCH /api/auth/me.
     *  ALWAYS overrides the app-wide non_null default: the pinned contract is
     *  "preferredName": string|null — the key is present even when unset. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String preferredName;
    private String role;
    private boolean hasProfile;
    private ClaimResponse activeClaim;
    /** Profiles the current user can access (own + accepted shares). Never null. */
    private List<ProfileSummaryDto> sharedProfiles;

    public UserResponse() {
    }

    public UserResponse(Long id, String email, String name, String preferredName, String role,
                        boolean hasProfile, ClaimResponse activeClaim, List<ProfileSummaryDto> sharedProfiles) {
        this.id = id;
        this.email = email;
        this.name = name;
        this.preferredName = preferredName;
        this.role = role;
        this.hasProfile = hasProfile;
        this.activeClaim = activeClaim;
        this.sharedProfiles = sharedProfiles != null ? sharedProfiles : Collections.emptyList();
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isHasProfile() {
        return hasProfile;
    }

    public void setHasProfile(boolean hasProfile) {
        this.hasProfile = hasProfile;
    }

    public ClaimResponse getActiveClaim() {
        return activeClaim;
    }

    public void setActiveClaim(ClaimResponse activeClaim) {
        this.activeClaim = activeClaim;
    }

    public List<ProfileSummaryDto> getSharedProfiles() {
        return sharedProfiles != null ? sharedProfiles : Collections.emptyList();
    }

    public void setSharedProfiles(List<ProfileSummaryDto> sharedProfiles) {
        this.sharedProfiles = sharedProfiles != null ? sharedProfiles : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String email;
        private String name;
        private String preferredName;
        private String role;
        private boolean hasProfile;
        private ClaimResponse activeClaim;
        private List<ProfileSummaryDto> sharedProfiles;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder preferredName(String preferredName) {
            this.preferredName = preferredName;
            return this;
        }

        public Builder role(String role) {
            this.role = role;
            return this;
        }

        public Builder hasProfile(boolean hasProfile) {
            this.hasProfile = hasProfile;
            return this;
        }

        public Builder activeClaim(ClaimResponse activeClaim) {
            this.activeClaim = activeClaim;
            return this;
        }

        public Builder sharedProfiles(List<ProfileSummaryDto> sharedProfiles) {
            this.sharedProfiles = sharedProfiles;
            return this;
        }

        public UserResponse build() {
            return new UserResponse(id, email, name, preferredName, role, hasProfile, activeClaim, sharedProfiles);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserResponse that = (UserResponse) o;
        return hasProfile == that.hasProfile &&
                Objects.equals(id, that.id) &&
                Objects.equals(email, that.email) &&
                Objects.equals(name, that.name) &&
                Objects.equals(preferredName, that.preferredName) &&
                Objects.equals(role, that.role) &&
                Objects.equals(activeClaim, that.activeClaim);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, email, name, preferredName, role, hasProfile, activeClaim);
    }

    @Override
    public String toString() {
        return "UserResponse(" +
                "id=" + id +
                ", email=" + email +
                ", name=" + name +
                ", preferredName=" + preferredName +
                ", role=" + role +
                ", hasProfile=" + hasProfile +
                ", activeClaim=" + activeClaim +
                ')';
    }
}
