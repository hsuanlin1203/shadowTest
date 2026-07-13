package com.example.shadowtest.model;

import java.time.Instant;

public record LogRecord(
        String traceId,
        String host,
        Instant timestamp,
        String docId,
        String index,
        String rawBody,
        String product
) {}
