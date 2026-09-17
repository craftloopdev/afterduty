package com.afterduty.dto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ServiceProfileRequest {
    private String branch;
    private String serviceStart;
    private String serviceEnd;
    private List<Map<String, Object>> dutyStations;
    private List<Map<String, Object>> deployments;
    private String mos;
    private List<String> exposureRisks;

    public ServiceProfileRequest() {
    }

    public ServiceProfileRequest(String branch, String serviceStart, String serviceEnd,
                                 List<Map<String, Object>> dutyStations,
                                 List<Map<String, Object>> deployments, String mos,
                                 List<String> exposureRisks) {
        this.branch = branch;
        this.serviceStart = serviceStart;
        this.serviceEnd = serviceEnd;
        this.dutyStations = dutyStations;
        this.deployments = deployments;
        this.mos = mos;
        this.exposureRisks = exposureRisks;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceProfileRequest that = (ServiceProfileRequest) o;
        return Objects.equals(branch, that.branch) &&
                Objects.equals(serviceStart, that.serviceStart) &&
                Objects.equals(serviceEnd, that.serviceEnd) &&
                Objects.equals(dutyStations, that.dutyStations) &&
                Objects.equals(deployments, that.deployments) &&
                Objects.equals(mos, that.mos) &&
                Objects.equals(exposureRisks, that.exposureRisks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(branch, serviceStart, serviceEnd, dutyStations, deployments, mos, exposureRisks);
    }

    @Override
    public String toString() {
        return "ServiceProfileRequest(" +
                "branch=" + branch +
                ", serviceStart=" + serviceStart +
                ", serviceEnd=" + serviceEnd +
                ", dutyStations=" + dutyStations +
                ", deployments=" + deployments +
                ", mos=" + mos +
                ", exposureRisks=" + exposureRisks +
                ')';
    }
}
