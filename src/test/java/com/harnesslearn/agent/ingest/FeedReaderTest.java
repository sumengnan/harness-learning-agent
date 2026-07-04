package com.harnesslearn.agent.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FeedReaderTest {

    private static final String RSS = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel>
          <item>
            <title>Harness CI 新特性</title>
            <link>https://harness.io/blog/ci-feature</link>
            <guid>guid-001</guid>
          </item>
          <item>
            <title>无 guid 的文章</title>
            <link>https://harness.io/blog/no-guid</link>
          </item>
        </channel></rss>""";

    @Test
    void parsesRssItems() {
        List<FeedItem> items = FeedReader.parseRss(RSS);
        assertThat(items).hasSize(2);
        assertThat(items.get(0).guid()).isEqualTo("guid-001");
        assertThat(items.get(0).url()).isEqualTo("https://harness.io/blog/ci-feature");
        assertThat(items.get(0).title()).isEqualTo("Harness CI 新特性");
    }

    @Test
    void guidFallsBackToLinkWhenMissing() {
        List<FeedItem> items = FeedReader.parseRss(RSS);
        assertThat(items.get(1).guid()).isEqualTo("https://harness.io/blog/no-guid");
    }

    @Test
    void malformedXmlYieldsEmptyNoThrow() {
        assertThat(FeedReader.parseRss("这不是 XML <<<")).isEmpty();
    }

    @Test
    void pageSourceYieldsSingleItem() {
        var src = new Source("blog", SourceType.PAGE, "https://harness.io/blog", List.of());
        List<FeedItem> items = new FeedReader().read(src);
        assertThat(items).singleElement()
            .satisfies(i -> assertThat(i.url()).isEqualTo("https://harness.io/blog"));
    }
}
