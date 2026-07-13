package com.example.shadowtest.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class LogRecordTest {
    @Test
    void holdsAllFields() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        LogRecord r = new LogRecord("t1", "api.example.com", now, "doc1", "prod-responses", "{\"a\":1}", "demo");
        assertThat(r.traceId()).isEqualTo("t1");
        assertThat(r.host()).isEqualTo("api.example.com");
        assertThat(r.timestamp()).isEqualTo(now);
        assertThat(r.docId()).isEqualTo("doc1");
        assertThat(r.index()).isEqualTo("prod-responses");
        assertThat(r.rawBody()).isEqualTo("{\"a\":1}");
        assertThat(r.product()).isEqualTo("demo");
    }
}
