package com.harnesslearn.agent.subagent;
import java.util.List;
public interface SubAgentDispatcher {
    <I,O> O dispatch(SubAgent<I,O> agent, I input, O fallback);
    <I,O> List<O> dispatchParallel(SubAgent<I,O> agent, List<I> inputs, O fallback);
}
