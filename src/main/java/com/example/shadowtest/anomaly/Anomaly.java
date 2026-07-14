package com.example.shadowtest.anomaly;

import java.time.Instant;

public record Anomaly(
        String jobId,
        String taskId,
        String product,
        Type type,
        String traceId,
        String host,
        Instant windowStart,
        Instant windowEnd,
        String prodBody,
        String shadowBody,
        String diff,
        Instant detectedAt
) {
    public enum Type { MISMATCH, UNMATCHED_PROD, UNMATCHED_SHADOW }
}
