package com.example.shadowtest.es;

import co.elastic.clients.elasticsearch._types.Refresh;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.engine.TimeWindow;
import com.example.shadowtest.model.LogRecord;
import com.example.shadowtest.support.ElasticsearchTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EsLogFetcherIT extends ElasticsearchTestBase {

    static ComparisonTaskConfig cfg;

    @BeforeAll
    static void seed() throws Exception {
        cfg = new ComparisonTaskConfig();
        cfg.setHost("api.example.com");
        cfg.setHostField("host");
        cfg.setTimeField("@timestamp");
        cfg.setTraceIdField("traceId");
        cfg.setBodyField("responseBody");
        cfg.setPageSize(2); // force pagination

        index("prod-responses", "t1", "api.example.com", "2026-07-13T00:01:00Z", "body-1");
        index("prod-responses", "t2", "api.example.com", "2026-07-13T00:02:00Z", "body-2");
        index("prod-responses", "t3", "api.example.com", "2026-07-13T00:03:00Z", "body-3");
        index("prod-responses", "other", "OTHER.host", "2026-07-13T00:04:00Z", "body-x");
        index("prod-responses", "late", "api.example.com", "2026-07-13T00:15:00Z", "body-late");
        client.indices().refresh(r -> r.index("prod-responses"));
    }

    static void index(String index, String traceId, String host, String ts, String body) throws Exception {
        client.index(i -> i
            .index(index)
            .refresh(Refresh.True)
            .document(Map.of(
                "traceId", traceId,
                "host", host,
                "@timestamp", ts,
                "responseBody", body)));
    }

    @Test
    void fetchesOnlyMatchingHostWithinWindowAcrossPages() {
        TimeWindow window = new TimeWindow(
            Instant.parse("2026-07-13T00:00:00Z"),
            Instant.parse("2026-07-13T00:10:00Z"));

        List<LogRecord> records = new EsLogFetcher(client).fetch("prod-responses", cfg, window);

        assertThat(records).extracting(LogRecord::traceId)
            .containsExactlyInAnyOrder("t1", "t2", "t3"); // not "other" (host), not "late" (window)
        assertThat(records).allSatisfy(r -> {
            assertThat(r.host()).isEqualTo("api.example.com");
            assertThat(r.rawBody()).startsWith("body-");
            assertThat(r.index()).isEqualTo("prod-responses");
            assertThat(r.docId()).isNotBlank();
        });
    }
}
