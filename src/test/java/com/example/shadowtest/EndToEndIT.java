package com.example.shadowtest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.example.shadowtest.job.ComparisonJobManager;
import com.example.shadowtest.job.JobStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class EndToEndIT {

    @Container
    static ElasticsearchContainer es = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.0"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        es.start();
        String[] hostPort = es.getHttpHostAddress().split(":");
        r.add("elasticsearch.host", () -> hostPort[0]);
        r.add("elasticsearch.port", () -> hostPort[1]);
        r.add("elasticsearch.scheme", () -> "http");
        // task config
        r.add("comparison.tasks.e2e.product", () -> "demo");
        r.add("comparison.tasks.e2e.prod-index", () -> "prod-e2e");
        r.add("comparison.tasks.e2e.shadow-index", () -> "shadow-e2e");
        r.add("comparison.tasks.e2e.host", () -> "api.example.com");
        r.add("comparison.tasks.e2e.host-field", () -> "host");
        r.add("comparison.tasks.e2e.time-field", () -> "@timestamp");
        r.add("comparison.tasks.e2e.trace-id-field", () -> "traceId");
        r.add("comparison.tasks.e2e.body-field", () -> "responseBody");
        r.add("comparison.tasks.e2e.window-minutes", () -> "10");
        r.add("comparison.tasks.e2e.page-size", () -> "500");
        r.add("comparison.tasks.e2e.start-time", () -> "2026-07-13T00:00:00Z");
        r.add("comparison.tasks.e2e.end-time", () -> "2026-07-13T00:30:00Z");
        r.add("anomaly.index-name", () -> "e2e-anomalies");
    }

    @Autowired ElasticsearchClient client;
    @Autowired ComparisonJobManager manager;

    void doc(String index, String traceId, String ts, String body) throws Exception {
        client.index(i -> i.index(index).refresh(Refresh.True).document(Map.of(
            "traceId", traceId, "host", "api.example.com", "@timestamp", ts, "responseBody", body)));
    }

    @Test
    void fullRunProducesExpectedAnomalies() throws Exception {
        // matching pair (same window)
        doc("prod-e2e", "match", "2026-07-13T00:01:00Z", "same");
        doc("shadow-e2e", "match", "2026-07-13T00:02:00Z", "same");
        // mismatch pair
        doc("prod-e2e", "diff", "2026-07-13T00:03:00Z", "prod-body");
        doc("shadow-e2e", "diff", "2026-07-13T00:04:00Z", "shadow-body");
        // cross-window match (prod window 1, shadow window 2)
        doc("prod-e2e", "cross", "2026-07-13T00:05:00Z", "x");
        doc("shadow-e2e", "cross", "2026-07-13T00:15:00Z", "x");
        // unmatched on each side
        doc("prod-e2e", "only-prod", "2026-07-13T00:06:00Z", "p");
        doc("shadow-e2e", "only-shadow", "2026-07-13T00:16:00Z", "s");
        client.indices().refresh(rq -> rq.index("prod-e2e", "shadow-e2e"));

        String jobId = manager.trigger("e2e");

        await().atMost(Duration.ofSeconds(30)).until(() ->
            manager.getJob(jobId).get().getStatus() == JobStatus.COMPLETED);

        client.indices().refresh(rq -> rq.index("e2e-anomalies"));
        long total = client.count(c -> c.index("e2e-anomalies")).count();
        // 1 mismatch + 2 unmatched = 3
        assertThat(total).isEqualTo(3L);

        var state = manager.getJob(jobId).get();
        assertThat(state.getMatched()).isEqualTo(2);   // "match" + "cross"
        assertThat(state.getMismatch()).isEqualTo(1);
        assertThat(state.getUnmatched()).isEqualTo(2);
    }
}
