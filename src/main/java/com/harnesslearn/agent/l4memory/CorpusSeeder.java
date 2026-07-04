package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.domain.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 启动种子语料摄取：读内置 JSON 逐条 {@code remember} 填充向量库。
 *
 * <p>best-effort：种子资源缺失 / JSON 解析失败 → 记 WARN 返回 0（不抛、不中断启动）；
 * 单条 {@code remember} 失败 → 记 WARN 跳过该条、继续其余。无 Spring 依赖，可直接 {@code new} 单测。
 */
public class CorpusSeeder {
    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);

    /** 种子条目。{@code tags} 供未来过滤维度，本轮检索期不消费。 */
    public record SeedEntry(String text, String uri, List<String> tags) {}

    private final LongTermMemory memory;
    private final String resourcePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public CorpusSeeder(LongTermMemory memory, String resourcePath) {
        if (memory == null) throw new IllegalArgumentException("memory 不能为空");
        if (resourcePath == null || resourcePath.isBlank())
            throw new IllegalArgumentException("resourcePath 不能为空");
        this.memory = memory;
        this.resourcePath = resourcePath;
    }

    /**
     * 读种子资源逐条 remember。
     * @return 成功摄取的条数（资源缺失/解析失败返回 0）
     */
    public int seed() {
        SeedEntry[] entries;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                log.warn("种子语料资源不存在，跳过摄取: {}", resourcePath);
                return 0;
            }
            entries = mapper.readValue(is, SeedEntry[].class);
        } catch (Exception e) {
            log.warn("种子语料解析失败，跳过摄取: {}", resourcePath, e);
            return 0;
        }
        int ok = 0;
        for (SeedEntry e : entries) {
            try {
                memory.remember(new MemoryItem(e.text(), Map.of(
                    "uri", e.uri() == null ? "" : e.uri(),
                    "tags", e.tags() == null ? "" : String.join(",", e.tags()))));
                ok++;
            } catch (RuntimeException ex) {
                log.warn("种子条目摄取失败，跳过: uri={}", e.uri(), ex);
            }
        }
        log.info("种子语料摄取完成: {}/{} 条", ok, entries.length);
        return ok;
    }
}
