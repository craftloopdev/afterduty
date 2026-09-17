package com.afterduty.dto;

import java.util.Objects;

public class AtomDto {
    private String type;
    private String value;
    private String source;
    private Double confidence;
    private String date;

    public AtomDto() {
    }

    public AtomDto(String type, String value, String source, Double confidence, String date) {
        this.type = type;
        this.value = value;
        this.source = source;
        this.confidence = confidence;
        this.date = date;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private String value;
        private String source;
        private Double confidence;
        private String date;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder value(String value) {
            this.value = value;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder confidence(Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder date(String date) {
            this.date = date;
            return this;
        }

        public AtomDto build() {
            return new AtomDto(type, value, source, confidence, date);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AtomDto that = (AtomDto) o;
        return Objects.equals(type, that.type) &&
                Objects.equals(value, that.value) &&
                Objects.equals(source, that.source) &&
                Objects.equals(confidence, that.confidence) &&
                Objects.equals(date, that.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, value, source, confidence, date);
    }

    @Override
    public String toString() {
        return "AtomDto(" +
                "type=" + type +
                ", value=" + value +
                ", source=" + source +
                ", confidence=" + confidence +
                ", date=" + date +
                ')';
    }
}
