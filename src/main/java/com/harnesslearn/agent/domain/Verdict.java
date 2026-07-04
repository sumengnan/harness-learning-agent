package com.harnesslearn.agent.domain;
import java.util.List;
public record Verdict(boolean pass, List<Issue> issues, double confidence) {}
