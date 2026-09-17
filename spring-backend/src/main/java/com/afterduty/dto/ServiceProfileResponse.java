package com.afterduty.dto;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ServiceProfileResponse {
    private Long id;
    private Long userId;
    private String branch;
    private String serviceStart;
    private String serviceEnd;
    private List<Map<String, Object>> dutyStations;
    private List<Map<String, Object>> deployments;
    private String mos;
    private List<String> exposureRisks;
    /** Derived + manual service periods, newest-first (null start sorts last).
     *  Never null — empty array when nothing is derivable. See {@link ServicePeriodDto}. */
    private List<ServicePeriodDto> servicePeriods = Collections.emptyList();

    public ServiceProfileResponse() {
    }

    public ServiceProfileResponse(Long id, Long userId, String branch, String serviceStart, String serviceEnd,
                                  List<Map<String, Object>> dutyStations, List<Map<String, Object>> deployments,
                                  String mos, List<String> exposureRisks, List<ServicePeriodDto> servicePeriods) {
        this.id = id;
        this.userId = userId;
        this.branch = branch;
        this.serviceStart = serviceStart;
        this.serviceEnd = serviceEnd;
        this.dutyStations = dutyStations;
        this.deployments = deployments;
        this.mos = mos;
        this.exposureRisks = exposureRisks;
        this.servicePeriods = servicePeriods != null ? servicePeriods : Collections.emptyList();
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

    public List<ServicePeriodDto> getServicePeriods() {
        return servicePeriods != null ? servicePeriods : Collections.emptyList();
    }

    public void setServicePeriods(List<ServicePeriodDto> servicePeriods) {
        this.servicePeriods = servicePeriods != null ? servicePeriods : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private Long userId;
        private String branch;
        private String serviceStart;
        private String serviceEnd;
        private List<Map<String, Object>> dutyStations;
        private List<Map<String, Object>> deployments;
        private String mos;
        private List<String> exposureRisks;
        private List<ServicePeriodDto> servicePeriods;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public Builder serviceStart(String serviceStart) {
            this.serviceStart = serviceStart;
            return this;
        }

        public Builder serviceEnd(String serviceEnd) {
            this.serviceEnd = serviceEnd;
            return this;
        }

        public Builder dutyStations(List<Map<String, Object>> dutyStations) {
            this.dutyStations = dutyStations;
            return this;
        }

        public Builder deployments(List<Map<String, Object>> deployments) {
            this.deployments = deployments;
            return this;
        }

        public Builder mos(String mos) {
            this.mos = mos;
            return this;
        }

        public Builder exposureRisks(List<String> exposureRisks) {
            this.exposureRisks = exposureRisks;
            return this;
        }

        public Builder servicePeriods(List<ServicePeriodDto> servicePeriods) {
            this.servicePeriods = servicePeriods;
            return this;
        }

        public ServiceProfileResponse build() {
            return new ServiceProfileResponse(id, userId, branch, serviceStart, serviceEnd,
                    dutyStations, deployments, mos, exposureRisks, servicePeriods);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceProfileResponse that = (ServiceProfileResponse) o;
        return Objects.equals(id, that.id) &&
                Objects.equals(userId, that.userId) &&
                Objects.equals(branch, that.branch) &&
                Objects.equals(serviceStart, that.serviceStart) &&
                Objects.equals(serviceEnd, that.serviceEnd) &&
                Objects.equals(dutyStations, that.dutyStations) &&
                Objects.equals(deployments, that.deployments) &&
                Objects.equals(mos, that.mos) &&
                Objects.equals(exposureRisks, that.exposureRisks) &&
                Objects.equals(getServicePeriods(), that.getServicePeriods());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, userId, branch, serviceStart, serviceEnd, dutyStations,
                deployments, mos, exposureRisks, getServicePeriods());
    }

    @Override
    public String toString() {
        return "ServiceProfileResponse(" +
                "id=" + id +
                ", userId=" + userId +
                ", branch=" + branch +
                ", serviceStart=" + serviceStart +
                ", serviceEnd=" + serviceEnd +
                ", dutyStations=" + dutyStations +
                ", deployments=" + deployments +
                ", mos=" + mos +
                ", exposureRisks=" + exposureRisks +
                ", servicePeriods=" + servicePeriods +
                ')';
    }
}
