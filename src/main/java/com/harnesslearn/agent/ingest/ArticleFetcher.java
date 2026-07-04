package com.harnesslearn.agent.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抓单条目正文并去样板（与 FetchPageTool 相同的 Jsoup 清洗选择器）。
 * best-effort：抓取失败返回 null。整篇上限 MAX，切块前的粗上限。
 */
public class ArticleFetcher {
    private static final Logger log = LoggerFactory.getLogger(ArticleFetcher.class);
    private static final int MAX = 20000;

    /** 抓取并清洗正文；失败返回 null。 */
    public String fetch(String url) {
        try {
            Document doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(15000).get();
            return extractText(doc);
        } catch (Exception e) {
            log.warn("正文抓取失败，跳过: url={}", url, e);
            return null;
        }
    }

    /** 去样板取正文，供单测直接喂 Document。 */
    static String extractText(Document doc) {
        doc.select("script,style,nav,footer,header,aside").remove();
        String text = doc.body() == null ? "" : doc.body().text();
        return text.length() > MAX ? text.substring(0, MAX) : text;
    }
}
