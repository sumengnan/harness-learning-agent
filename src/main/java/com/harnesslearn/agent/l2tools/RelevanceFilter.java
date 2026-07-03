package com.harnesslearn.agent.l2tools;

import com.harnesslearn.agent.domain.RetrievedChunk;
import dev.langchain4j.model.embedding.EmbeddingModel;
import java.util.ArrayList;
import java.util.List;

/** 相关性闸门 + 去重：全部发生在信息进入上下文之前。 */
public class RelevanceFilter {
    public record Result(List<RetrievedChunk> kept, int droppedCount) {}

    private final EmbeddingModel embed;
    private final float[] centroid;
    private final double threshold;    // τ
    private final double dedupSim = 0.97;

    // borderlineDelta：预留给未来 τ±δ 边界复判的 LLM 二分类，本版靠阈值单闸，暂不接线
    public RelevanceFilter(EmbeddingModel embed, List<String> anchors, double threshold, double borderlineDelta) {
        this.embed = embed;
        this.threshold = threshold;
        this.centroid = mean(anchors.stream().map(a -> embed.embed(a).content().vector()).toList());
    }

    public Result filter(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> kept = new ArrayList<>();
        List<float[]> keptVecs = new ArrayList<>();
        int dropped = 0;
        for (RetrievedChunk c : chunks) {
            float[] v = embed.embed(c.text()).content().vector();
            double rel = cosine(v, centroid);
            if (rel < threshold) { dropped++; continue; }              // 剔垃圾
            boolean dup = keptVecs.stream().anyMatch(kv -> cosine(kv, v) >= dedupSim);
            if (dup) { dropped++; continue; }                          // 去重
            kept.add(new RetrievedChunk(c.id(), c.sourceUri(), c.text(), rel));
            keptVecs.add(v);
        }
        return new Result(kept, dropped);
    }

    private static float[] mean(List<float[]> vs) {
        float[] m = new float[vs.get(0).length];
        for (float[] v : vs) for (int i = 0; i < v.length; i++) m[i] += v[i];
        for (int i = 0; i < m.length; i++) m[i] /= vs.size();
        return m;
    }
    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
        return dot / (Math.sqrt(na)*Math.sqrt(nb) + 1e-9);
    }
}
