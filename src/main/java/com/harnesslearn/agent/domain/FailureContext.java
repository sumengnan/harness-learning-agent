package com.harnesslearn.agent.domain;
public record FailureContext(String failureType, int attempt, String detail) {}
