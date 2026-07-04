package com.harnesslearn.agent.ingest;

import java.util.ArrayList;
import java.util.List;

/** 清洗后正文按段落+长度贪心切块（≤maxChars/块），单段超限则硬切。v1 无块间重叠。 */
public class Chunker {
    private final int maxChars;

    public Chunker(int maxChars) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars 必须为正");
        this.maxChars = maxChars;
    }

    public List<String> split(String body) {
        List<String> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        for (String para : body.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (p.isEmpty()) continue;
            if (p.length() > maxChars) {                 // 单段超限，硬切
                flush(cur, out);
                for (int i = 0; i < p.length(); i += maxChars)
                    out.add(p.substring(i, Math.min(i + maxChars, p.length())));
                continue;
            }
            if (cur.length() + p.length() + 1 > maxChars) flush(cur, out);
            if (cur.length() > 0) cur.append('\n');
            cur.append(p);
        }
        flush(cur, out);
        return out;
    }

    private void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
    }
}
