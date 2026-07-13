package com.example.shadowtest.engine;

import com.example.shadowtest.anomaly.Anomaly;
import com.example.shadowtest.anomaly.AnomalyWriter;
import com.example.shadowtest.anomaly.DiffUtil;
import com.example.shadowtest.comparator.ComparatorRegistry;
import com.example.shadowtest.comparator.ResponseComparator;
import com.example.shadowtest.comparator.Signature;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.engine.MatchBuffer.Pair;
import com.example.shadowtest.engine.MatchBuffer.Side;
import com.example.shadowtest.es.EsLogFetcher;
import com.example.shadowtest.job.JobState;
import com.example.shadowtest.model.LogRecord;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs one comparison job: iterate windows, pair by traceId, compare, emit anomalies. */
@Component
public class ComparisonEngine {

    private final EsLogFetcher fetcher;
    private final ComparatorRegistry registry;
    private final AnomalyWriter writer;

    public ComparisonEngine(EsLogFetcher fetcher, ComparatorRegistry registry, AnomalyWriter writer) {
        this.fetcher = fetcher;
        this.registry = registry;
        this.writer = writer;
    }

    public void run(String jobId, String taskId, ComparisonTaskConfig cfg, JobState state) {
        ResponseComparator comparator = registry.forProduct(cfg.getProduct());
        MatchBuffer buffer = new MatchBuffer();

        Instant start = Instant.parse(cfg.getStartTime());
        Instant end = Instant.parse(cfg.getEndTime());
        Duration window = Duration.ofMinutes(cfg.getWindowMinutes());

        TimeWindowIterator windows = new TimeWindowIterator(start, end, window);
        state.setTotalWindows(windows.totalWindows());

        while (windows.hasNext()) {
            TimeWindow tw = windows.next();
            List<Anomaly> anomalies = new ArrayList<>();

            List<LogRecord> prod = fetcher.fetch(cfg.getProdIndex(), cfg, tw);
            for (LogRecord r : prod) {
                offer(buffer, Side.PROD, r, comparator, cfg, taskId, jobId, tw, state, anomalies);
            }
            List<LogRecord> shadow = fetcher.fetch(cfg.getShadowIndex(), cfg, tw);
            for (LogRecord r : shadow) {
                offer(buffer, Side.SHADOW, r, comparator, cfg, taskId, jobId, tw, state, anomalies);
            }

            writer.write(anomalies);
            state.incCurrentWindow();
        }

        // Whatever remains after the whole range is unmatched.
        List<Anomaly> leftovers = new ArrayList<>();
        Instant now = Instant.now();
        for (MatchBuffer.Entry e : buffer.remainingProd()) {
            leftovers.add(unmatched(Anomaly.Type.UNMATCHED_PROD, e.record(), jobId, taskId, cfg, now));
        }
        for (MatchBuffer.Entry e : buffer.remainingShadow()) {
            leftovers.add(unmatched(Anomaly.Type.UNMATCHED_SHADOW, e.record(), jobId, taskId, cfg, now));
        }
        state.addUnmatched(leftovers.size());
        writer.write(leftovers);
    }

    private void offer(MatchBuffer buffer, Side side, LogRecord record, ResponseComparator comparator,
                       ComparisonTaskConfig cfg, String taskId, String jobId, TimeWindow tw,
                       JobState state, List<Anomaly> anomalies) {
        Signature sig = comparator.signature(record);
        buffer.offer(side, record, sig).ifPresent(pair -> {
            if (comparator.matches(pair.prod().signature(), pair.shadow().signature())) {
                state.incMatched();
            } else {
                anomalies.add(mismatch(pair, jobId, taskId, cfg, tw));
                state.incMismatch();
            }
        });
    }

    private Anomaly mismatch(Pair pair, String jobId, String taskId, ComparisonTaskConfig cfg, TimeWindow tw) {
        String prodBody = pair.prod().record().rawBody();
        String shadowBody = pair.shadow().record().rawBody();
        return new Anomaly(jobId, taskId, cfg.getProduct(), Anomaly.Type.MISMATCH,
            pair.prod().record().traceId(), pair.prod().record().host(),
            tw.start(), tw.end(),
            prodBody, shadowBody, DiffUtil.lineDiff(prodBody, shadowBody), Instant.now());
    }

    private Anomaly unmatched(Anomaly.Type type, LogRecord r, String jobId, String taskId,
                              ComparisonTaskConfig cfg, Instant now) {
        boolean isProd = type == Anomaly.Type.UNMATCHED_PROD;
        return new Anomaly(jobId, taskId, cfg.getProduct(), type,
            r.traceId(), r.host(), null, null,
            isProd ? r.rawBody() : null,
            isProd ? null : r.rawBody(),
            null, now);
    }
}
