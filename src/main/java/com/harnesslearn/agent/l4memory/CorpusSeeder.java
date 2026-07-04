package com.harnesslearn.agent.l4memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harnesslearn.agent.ingest.CorpusChunk;
import com.harnesslearn.agent.ingest.CorpusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.util.List;

/**
 * 启动种子语料摄取：读内置 JSON，空库时逐条写入 corpus_chunk（SQLite 真相源），
 * 之后由 CorpusIndexRebuilder 统一重建内存索引——种子与抓取语料走同一持久化+重建路径。
 *
 * <p>best-effort：库非空跳过；资源缺失/解析失败 → WARN 返回 0（不抛、不中断启动）；
 * 单条写入失败 → WARN 跳过该条。无 Spring 依赖，可直接 {@code new} 单测。
 */
public class CorpusSeeder {
    private static final Logger log = LoggerFactory.getLogger(CorpusSeeder.class);

    /** 种子条目。{@code tags} 供未来过滤维度，本轮检索期不消费。 */
    public record SeedEntry(String text, String uri, List<String> tags) {}

    private final CorpusRepository repo;
    private final String resourcePath;
    private final ObjectMapper mapper = new ObjectMapper();

    public CorpusSeeder(CorpusRepository repo, String resourcePath) {
        if (repo == null) throw new IllegalArgumentException("repo 不能为空");
        if (resourcePath == null || resourcePath.isBlank())
            throw new IllegalArgumentException("resourcePath 不能为空");
        this.repo = repo;
        this.resourcePath = resourcePath;
    }

    /**
     * 空库时读种子资源逐条写入 SQLite。
     * @return 成功写入的条数（库非空 / 资源缺失 / 解析失败均返回 0）
     */
    public int seed() {
        int existing;
        try {
            existing = repo.chunkCount();
        } catch (RuntimeException e) {
            log.warn("种子播种检查语料库失败，跳过播种", e);
            return 0;
        }
        if (existing > 0) {
            log.info("语料库非空，跳过种子播种");
            return 0;
        }
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
                repo.upsertChunk(new CorpusChunk(
                    "seed:" + e.uri(), "seed", e.uri(), null, 0, e.text(), null));
                ok++;
            } catch (RuntimeException ex) {
                log.warn("种子条目写入失败，跳过: uri={}", e.uri(), ex);
            }
        }
        log.info("种子语料写入 SQLite 完成: {}/{} 条", ok, entries.length);
        return ok;
    }
}
