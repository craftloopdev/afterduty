package com.afterduty.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Objects;

public class QuickAddRequest {
    @NotBlank
    private String text;

    public QuickAddRequest() {
    }

    public QuickAddRequest(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        QuickAddRequest that = (QuickAddRequest) o;
        return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text);
    }

    @Override
    public String toString() {
        return "QuickAddRequest(" +
                "text=" + text +
                ')';
    }
}
