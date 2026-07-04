package com.harnesslearn.agent.observability;
import java.util.List;

/** Agent 执行轨迹落盘：每步一条 {@link TraceStep}，供事后回放与调试。 */
public interface TraceStore {
    /**
     * 追加一条轨迹步。时间戳（ts）由实现在写入时以毫秒为单位记录；
     * {@code seq} 由调用方保证在同一 run 内单调递增。
     */
    void append(TraceStep step);

    /**
     * 读取某次运行的全部轨迹步，按 {@code seq} 升序返回。
     * 未命中（无该 runId 的记录）返回空 List，不抛异常。
     */
    List<TraceStep> load(String runId);
}
