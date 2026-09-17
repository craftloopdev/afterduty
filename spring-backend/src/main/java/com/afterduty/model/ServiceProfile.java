package com.afterduty.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Entity
@Table(name = "service_profiles")
public class ServiceProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", unique = true, nullable = false, insertable = false, updatable = false)
    private Long userId;

    private String branch;

    @Column(name = "service_start")
    private String serviceStart;

    @Column(name = "service_end")
    private String serviceEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "duty_stations", columnDefinition = "text")
    private List<Map<String, Object>> dutyStations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deployments", columnDefinition = "text")
    private List<Map<String, Object>> deployments;

    private String mos;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exposure_risks", columnDefinition = "text")
    private List<String> exposureRisks;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    public ServiceProfile() {
    }

    public ServiceProfile(Long id, Long userId, String branch, String serviceStart, String serviceEnd, List<Map<String, Object>> dutyStations, List<Map<String, Object>> deployments, String mos, List<String> exposureRisks, User user) {
        this.id = id;
        this.userId = userId;
        this.branch = branch;
        this.serviceStart = serviceStart;
        this.serviceEnd = serviceEnd;
        this.dutyStations = dutyStations;
        this.deployments = deployments;
        this.mos = mos;
        this.exposureRisks = exposureRisks;
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

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public String getServiceStart() {
        return serviceStart;
    }

    public void setServiceStart(String serviceStart) {
        this.serviceStart = serviceStart;
    }

    public String getServiceEnd() {
        return serviceEnd;
    }

    public void setServiceEnd(String serviceEnd) {
        this.serviceEnd = serviceEnd;
    }

    public List<Map<String, Object>> getDutyStations() {
        return dutyStations;
    }

    public void setDutyStations(List<Map<String, Object>> dutyStations) {
        this.dutyStations = dutyStations;
    }

    public List<Map<String, Object>> getDeployments() {
        return deployments;
    }

    public void setDeployments(List<Map<String, Object>> deployments) {
        this.deployments = deployments;
    }

    public String getMos() {
        return mos;
    }

    public void setMos(String mos) {
        this.mos = mos;
    }

    public List<String> getExposureRisks() {
        return exposureRisks;
    }

    public void setExposureRisks(List<String> exposureRisks) {
        this.exposureRisks = exposureRisks;
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
        ServiceProfile that = (ServiceProfile) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(branch, that.branch) &&
                Objects.equals(serviceStart, that.serviceStart) &&
                Objects.equals(serviceEnd, that.serviceEnd) &&
                Objects.equals(dutyStations, that.dutyStations) &&
                Objects.equals(deployments, that.deployments) &&
                Objects.equals(mos, that.mos) &&
                Objects.equals(exposureRisks, that.exposureRisks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, branch, serviceStart, serviceEnd, dutyStations, deployments, mos, exposureRisks);
    }

    @Override
    public String toString() {
        return "ServiceProfile(" +
                "id=" + id +
                ", userId=" + userId +
                ", branch=" + branch +
                ", serviceStart=" + serviceStart +
                ", serviceEnd=" + serviceEnd +
                ", dutyStations=" + dutyStations +
                ", deployments=" + deployments +
                ", mos=" + mos +
                ", exposureRisks=" + exposureRisks +
                ')';
    }

    public static ServiceProfileBuilder builder() {
        return new ServiceProfileBuilder();
    }

    public static class ServiceProfileBuilder {
        private Long id;
        private Long userId;
        private String branch;
        private String serviceStart;
        private String serviceEnd;
        private List<Map<String, Object>> dutyStations;
        private List<Map<String, Object>> deployments;
        private String mos;
        private List<String> exposureRisks;
        private User user;

        ServiceProfileBuilder() {
        }

        public ServiceProfileBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public ServiceProfileBuilder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public ServiceProfileBuilder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public ServiceProfileBuilder serviceStart(String serviceStart) {
            this.serviceStart = serviceStart;
            return this;
        }

        public ServiceProfileBuilder serviceEnd(String serviceEnd) {
            this.serviceEnd = serviceEnd;
            return this;
        }

        public ServiceProfileBuilder dutyStations(List<Map<String, Object>> dutyStations) {
            this.dutyStations = dutyStations;
            return this;
        }

        public ServiceProfileBuilder deployments(List<Map<String, Object>> deployments) {
            this.deployments = deployments;
            return this;
        }

        public ServiceProfileBuilder mos(String mos) {
            this.mos = mos;
            return this;
        }

        public ServiceProfileBuilder exposureRisks(List<String> exposureRisks) {
            this.exposureRisks = exposureRisks;
            return this;
        }

        public ServiceProfileBuilder user(User user) {
            this.user = user;
            return this;
        }

        public ServiceProfile build() {
            return new ServiceProfile(id, userId, branch, serviceStart, serviceEnd, dutyStations, deployments, mos, exposureRisks, user);
        }
    }
}
