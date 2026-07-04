package com.harnesslearn.agent.subagent;
public interface SubAgent<I, O> {
    String name();
    O run(I input);
}
