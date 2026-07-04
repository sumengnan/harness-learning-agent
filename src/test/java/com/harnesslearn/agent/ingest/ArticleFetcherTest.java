package com.harnesslearn.agent.ingest;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleFetcherTest {

    @Test
    void extractsBodyTextStrippingBoilerplate() {
        var doc = Jsoup.parse("""
            <html><body>
              <nav>菜单导航</nav>
              <header>页眉</header>
              <p>这是正文的核心内容。</p>
              <footer>版权所有</footer>
              <script>var x=1;</script>
            </body></html>""");
        String text = ArticleFetcher.extractText(doc);
        assertThat(text).contains("正文的核心内容");
        assertThat(text).doesNotContain("菜单导航");
        assertThat(text).doesNotContain("版权所有");
        assertThat(text).doesNotContain("var x");
    }

    @Test
    void fetchReturnsNullOnBadUrl() {
        assertThat(new ArticleFetcher().fetch("http://invalid.invalid")).isNull();
    }
}
