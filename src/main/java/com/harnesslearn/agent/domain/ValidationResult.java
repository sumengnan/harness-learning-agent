package com.harnesslearn.agent.domain;
public record ValidationResult(boolean valid, String reason) {
    public static ValidationResult ok() { return new ValidationResult(true, null); }
    public static ValidationResult invalid(String reason) { return new ValidationResult(false, reason); }
}
