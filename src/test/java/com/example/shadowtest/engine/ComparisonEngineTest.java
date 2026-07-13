package com.example.shadowtest.engine;

import com.example.shadowtest.anomaly.Anomaly;
import com.example.shadowtest.anomaly.AnomalyWriter;
import com.example.shadowtest.comparator.ComparatorRegistry;
import com.example.shadowtest.comparator.HashResponseComparator;
import com.example.shadowtest.config.AnomalyProperties;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.es.EsLogFetcher;
import com.example.shadowtest.job.JobState;
import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonEngineTest {

    private LogRecord rec(String index, String traceId, String body, String ts) {
        return new LogRecord(traceId, "h", Instant.parse(ts), "doc-" + traceId, index, body, "demo");
    }

    // Fake fetcher returns canned records keyed by (index, window-start).
    static class FakeFetcher extends EsLogFetcher {
        final Map<String, List<LogRecord>> byIndexAndWindow;
        FakeFetcher(Map<String, List<LogRecord>> data) { super(null); this.byIndexAndWindow = data; }
        @Override public List<LogRecord> fetch(String index, ComparisonTaskConfig cfg, TimeWindow w) {
            return byIndexAndWindow.getOrDefault(index + "@" + w.start(), List.of());
        }
    }

    static class CapturingWriter extends AnomalyWriter {
        final List<Anomaly> captured = new ArrayList<>();
        CapturingWriter() { super(null, new AnomalyProperties()); }
        @Override public void write(List<Anomaly> anomalies) { captured.addAll(anomalies); }
    }

    private ComparisonTaskConfig config() {
        ComparisonTaskConfig cfg = new ComparisonTaskConfig();
        cfg.setProduct("demo");
        cfg.setProdIndex("prod");
        cfg.setShadowIndex("shadow");
        cfg.setWindowMinutes(10);
        cfg.setStartTime("2026-07-13T00:00:00Z");
        cfg.setEndTime("2026-07-13T00:20:00Z");
        return cfg;
    }

    private ComparatorRegistry registry() {
        HashResponseComparator def = new HashResponseComparator();
        return new ComparatorRegistry(List.of(def), def);
    }

    @Test
    void matchingRecordsProduceNoAnomalies() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "t1", "same", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:00:00Z", List.of(rec("shadow", "t1", "same", "2026-07-13T00:02:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).isEmpty();
        assertThat(state.getMatched()).isEqualTo(1);
        assertThat(state.getTotalWindows()).isEqualTo(2);
    }

    @Test
    void mismatchProducesMismatchAnomalyWithBodies() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "t1", "prod-body", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:00:00Z", List.of(rec("shadow", "t1", "shadow-body", "2026-07-13T00:02:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).hasSize(1);
        Anomaly a = writer.captured.get(0);
        assertThat(a.type()).isEqualTo(Anomaly.Type.MISMATCH);
        assertThat(a.prodBody()).isEqualTo("prod-body");
        assertThat(a.shadowBody()).isEqualTo("shadow-body");
        assertThat(a.diff()).contains("+ shadow-body");
        assertThat(state.getMismatch()).isEqualTo(1);
    }

    @Test
    void crossWindowPairMatches() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "t1", "same", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:10:00Z", List.of(rec("shadow", "t1", "same", "2026-07-13T00:11:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).isEmpty();
        assertThat(state.getMatched()).isEqualTo(1);
    }

    @Test
    void leftoverRecordsBecomeUnmatchedAnomalies() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "only-prod", "p", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:10:00Z", List.of(rec("shadow", "only-shadow", "s", "2026-07-13T00:11:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).extracting(Anomaly::type)
            .containsExactlyInAnyOrder(Anomaly.Type.UNMATCHED_PROD, Anomaly.Type.UNMATCHED_SHADOW);
        assertThat(state.getUnmatched()).isEqualTo(2);
    }
}
