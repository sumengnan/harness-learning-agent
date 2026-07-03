package com.harnesslearn.agent.domain;
import java.util.ArrayList;
import java.util.List;
public final class WorkingState {
    private final String runId;
    private final String goal;
    private final int stepBudget;
    private final List<String> completedSteps = new ArrayList<>();
    private final List<String> openQuestions = new ArrayList<>();
    private WorkingState(String runId, String goal, int stepBudget) {
        this.runId = runId; this.goal = goal; this.stepBudget = stepBudget;
    }
    public static WorkingState start(String runId, String goal, int stepBudget) {
        return new WorkingState(runId, goal, stepBudget);
    }
    public void recordStep(String desc) { completedSteps.add(desc); }
    public void addOpenQuestion(String q) { openQuestions.add(q); }
    public String runId() { return runId; }
    public String goal() { return goal; }
    public List<String> completedSteps() { return List.copyOf(completedSteps); }
    public List<String> openQuestions() { return List.copyOf(openQuestions); }
    public int stepsUsed() { return completedSteps.size(); }
    public int budgetRemaining() { return stepBudget - stepsUsed(); }
    public boolean budgetExhausted() { return budgetRemaining() <= 0; }
}
