package com.afterduty.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "claim_scenarios")
public class ClaimScenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_ids", columnDefinition = "text")
    private List<Long> conditionIds;

    @Column(name = "combined_rating")
    private Integer combinedRating = 0;

    @Column(name = "exact_value")
    private Double exactValue = 0.0;

    @Column(name = "monthly_estimate")
    private Double monthlyEstimate = 0.0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation_steps", columnDefinition = "text")
    private List<String> calculationSteps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rating_overrides", columnDefinition = "text")
    private Map<String, Integer> ratingOverrides;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_conditions", columnDefinition = "text")
    private List<Map<String, Object>> customConditions;

    @Column(name = "married")
    private Boolean married = false;

    @Column(name = "num_children")
    private Integer numChildren = 0;

    @Column(name = "num_dependent_parents")
    private Integer numDependentParents = 0;

    @Column(name = "smc_k")
    private Boolean smcK = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private User user;

    public ClaimScenario() {
    }

    public ClaimScenario(Long id, Long userId, String name, List<Long> conditionIds, Integer combinedRating, Double exactValue, Double monthlyEstimate, List<String> calculationSteps, Map<String, Integer> ratingOverrides, List<Map<String, Object>> customConditions, Boolean married, Integer numChildren, Integer numDependentParents, Boolean smcK, Instant createdAt, User user) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.conditionIds = conditionIds;
        this.combinedRating = combinedRating;
        this.exactValue = exactValue;
        this.monthlyEstimate = monthlyEstimate;
        this.calculationSteps = calculationSteps;
        this.ratingOverrides = ratingOverrides;
        this.customConditions = customConditions;
        this.married = married;
        this.numChildren = numChildren;
        this.numDependentParents = numDependentParents;
        this.smcK = smcK;
        this.createdAt = createdAt;
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Long> getConditionIds() {
        return conditionIds;
    }

    public void setConditionIds(List<Long> conditionIds) {
        this.conditionIds = conditionIds;
    }

    public Integer getCombinedRating() {
        return combinedRating;
    }

    public void setCombinedRating(Integer combinedRating) {
        this.combinedRating = combinedRating;
    }

    public Double getExactValue() {
        return exactValue;
    }

    public void setExactValue(Double exactValue) {
        this.exactValue = exactValue;
    }

    public Double getMonthlyEstimate() {
        return monthlyEstimate;
    }

    public void setMonthlyEstimate(Double monthlyEstimate) {
        this.monthlyEstimate = monthlyEstimate;
    }

    public List<String> getCalculationSteps() {
        return calculationSteps;
    }

    public void setCalculationSteps(List<String> calculationSteps) {
        this.calculationSteps = calculationSteps;
    }

    public Map<String, Integer> getRatingOverrides() {
        return ratingOverrides;
    }

    public void setRatingOverrides(Map<String, Integer> ratingOverrides) {
        this.ratingOverrides = ratingOverrides;
    }

    public List<Map<String, Object>> getCustomConditions() {
        return customConditions;
    }

    public void setCustomConditions(List<Map<String, Object>> customConditions) {
        this.customConditions = customConditions;
    }

    public Boolean getMarried() { return married; }
    public void setMarried(Boolean married) { this.married = married; }

    public Integer getNumChildren() { return numChildren; }
    public void setNumChildren(Integer numChildren) { this.numChildren = numChildren; }

    public Integer getNumDependentParents() { return numDependentParents; }
    public void setNumDependentParents(Integer numDependentParents) { this.numDependentParents = numDependentParents; }

    public Boolean getSmcK() { return smcK; }
    public void setSmcK(Boolean smcK) { this.smcK = smcK; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ClaimScenario that = (ClaimScenario) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(name, that.name) &&
                Objects.equals(conditionIds, that.conditionIds) &&
                Objects.equals(combinedRating, that.combinedRating) &&
                Objects.equals(exactValue, that.exactValue) &&
                Objects.equals(monthlyEstimate, that.monthlyEstimate) &&
                Objects.equals(calculationSteps, that.calculationSteps) &&
                Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, name, conditionIds, combinedRating, exactValue, monthlyEstimate, calculationSteps, createdAt);
    }

    @Override
    public String toString() {
        return "ClaimScenario(" +
                "id=" + id +
                ", userId=" + userId +
                ", name=" + name +
                ", conditionIds=" + conditionIds +
                ", combinedRating=" + combinedRating +
                ", exactValue=" + exactValue +
                ", monthlyEstimate=" + monthlyEstimate +
                ", calculationSteps=" + calculationSteps +
                ", createdAt=" + createdAt +
                ')';
    }

    public static ClaimScenarioBuilder builder() {
        return new ClaimScenarioBuilder();
    }

    public static class ClaimScenarioBuilder {
        private Long id;
        private Long userId;
        private String name;
        private List<Long> conditionIds;
        private Integer combinedRating = 0;
        private Double exactValue = 0.0;
        private Double monthlyEstimate = 0.0;
        private List<String> calculationSteps;
        private Map<String, Integer> ratingOverrides;
        private List<Map<String, Object>> customConditions;
        private Instant createdAt = Instant.now();
        private User user;

        ClaimScenarioBuilder() {
        }

        public ClaimScenarioBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ClaimScenarioBuilder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public ClaimScenarioBuilder name(String name) {
            this.name = name;
            return this;
        }

        public ClaimScenarioBuilder conditionIds(List<Long> conditionIds) {
            this.conditionIds = conditionIds;
            return this;
        }

        public ClaimScenarioBuilder combinedRating(Integer combinedRating) {
            this.combinedRating = combinedRating;
            return this;
        }

        public ClaimScenarioBuilder exactValue(Double exactValue) {
            this.exactValue = exactValue;
            return this;
        }

        public ClaimScenarioBuilder monthlyEstimate(Double monthlyEstimate) {
            this.monthlyEstimate = monthlyEstimate;
            return this;
        }

        public ClaimScenarioBuilder calculationSteps(List<String> calculationSteps) {
            this.calculationSteps = calculationSteps;
            return this;
        }

        public ClaimScenarioBuilder ratingOverrides(Map<String, Integer> ratingOverrides) {
            this.ratingOverrides = ratingOverrides;
            return this;
        }

        public ClaimScenarioBuilder customConditions(List<Map<String, Object>> customConditions) {
            this.customConditions = customConditions;
            return this;
        }

        private Boolean married = false;
        private Integer numChildren = 0;
        private Integer numDependentParents = 0;
        private Boolean smcK = false;

        public ClaimScenarioBuilder married(Boolean married) { this.married = married; return this; }
        public ClaimScenarioBuilder numChildren(Integer numChildren) { this.numChildren = numChildren; return this; }
        public ClaimScenarioBuilder numDependentParents(Integer numDependentParents) { this.numDependentParents = numDependentParents; return this; }
        public ClaimScenarioBuilder smcK(Boolean smcK) { this.smcK = smcK; return this; }

        public ClaimScenarioBuilder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public ClaimScenarioBuilder user(User user) {
            this.user = user;
            return this;
        }

        public ClaimScenario build() {
            return new ClaimScenario(id, userId, name, conditionIds, combinedRating, exactValue, monthlyEstimate, calculationSteps, ratingOverrides, customConditions, married, numChildren, numDependentParents, smcK, createdAt, user);
        }
    }
}
