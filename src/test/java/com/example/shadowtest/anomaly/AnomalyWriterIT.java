package com.example.shadowtest.anomaly;

import co.elastic.clients.elasticsearch._types.Refresh;
import com.example.shadowtest.config.AnomalyProperties;
import com.example.shadowtest.support.ElasticsearchTestBase;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AnomalyWriterIT extends ElasticsearchTestBase {

    @Test
    void bulkWritesAnomaliesToConfiguredIndex() throws Exception {
        AnomalyProperties props = new AnomalyProperties();
        props.setIndexName("test-anomalies");
        AnomalyWriter writer = new AnomalyWriter(client, props);

        Anomaly a = new Anomaly("job1", "task-a", "alpha", Anomaly.Type.MISMATCH,
            "t1", "api.example.com",
            Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:10:00Z"),
            "prod-body", "shadow-body", "- prod-body\n+ shadow-body\n", Instant.now());

        writer.write(List.of(a));
        client.indices().refresh(r -> r.index("test-anomalies"));

        var count = client.count(c -> c.index("test-anomalies")).count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void emptyListIsNoOp() {
        AnomalyProperties props = new AnomalyProperties();
        props.setIndexName("test-anomalies-empty");
        AnomalyWriter writer = new AnomalyWriter(client, props);
        writer.write(List.of()); // must not throw
    }
}
