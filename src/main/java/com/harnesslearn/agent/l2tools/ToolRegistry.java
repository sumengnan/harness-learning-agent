package com.harnesslearn.agent.l2tools;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class ToolRegistry {
    private final Map<String, Tool> byName = new LinkedHashMap<>();
    /** 工具名不得重复，重复即抛 IllegalArgumentException。 */
    public ToolRegistry(List<Tool> tools) {
        for (Tool t : tools) {
            if (byName.put(t.name(), t) != null)
                throw new IllegalArgumentException("工具名重复: " + t.name());
        }
    }
    public List<String> names() { return List.copyOf(byName.keySet()); }
    /** @throws IllegalArgumentException 当 name 未注册 */
    public Tool get(String name) {
        Tool t = byName.get(name);
        if (t == null) throw new IllegalArgumentException("未知工具: " + name);
        return t;
    }
}
