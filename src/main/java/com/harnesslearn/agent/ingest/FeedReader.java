package com.harnesslearn.agent.ingest;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 拉一个来源产出条目清单。RSS 走 XML 解析（支持 RSS &lt;item&gt; 与 Atom &lt;entry&gt;）；
 * PAGE 把配置 URL 本身当作一篇文章。best-effort：抓取/解析失败 → WARN 返回空列表，不抛。
 */
public class FeedReader {
    private static final Logger log = LoggerFactory.getLogger(FeedReader.class);

    public List<FeedItem> read(Source source) {
        try {
            if (source.type() == SourceType.PAGE) {
                return List.of(new FeedItem(source.url(), source.url(), null, null));
            }
            Document doc = Jsoup.connect(source.url())
                .userAgent("Mozilla/5.0").timeout(15000)
                .ignoreContentType(true).parser(Parser.xmlParser()).get();
            return extract(doc);
        } catch (Exception e) {
            log.warn("来源抓取失败，跳过: id={} url={}", source.id(), source.url(), e);
            return List.of();
        }
    }

    /** 供单测直接喂 RSS/Atom XML 字符串。 */
    static List<FeedItem> parseRss(String xml) {
        return extract(Jsoup.parse(xml, "", Parser.xmlParser()));
    }

    private static List<FeedItem> extract(Document doc) {
        List<FeedItem> items = new ArrayList<>();
        for (Element it : doc.select("item, entry")) {
            String link = linkOf(it);
            if (link == null || link.isBlank()) continue;
            String guid = firstNonBlank(text(it, "guid"), text(it, "id"), link);
            items.add(new FeedItem(guid, link, text(it, "title"), null));
        }
        return items;
    }

    private static String linkOf(Element item) {
        Element l = item.selectFirst("link");
        if (l == null) return null;
        String href = l.attr("href");                 // Atom: <link href="..."/>
        return (href != null && !href.isBlank()) ? href : l.text();  // RSS: <link>...</link>
    }

    private static String text(Element parent, String tag) {
        Element e = parent.selectFirst(tag);
        return e == null ? null : e.text();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
