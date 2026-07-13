package com.example.shadowtest.engine;

import com.example.shadowtest.comparator.Signature;
import com.example.shadowtest.model.LogRecord;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pairs prod and shadow records by traceId across windows.
 * Holds full records so a mismatch can be diffed directly, and leftover
 * records can be reported as unmatched with their bodies.
 * Not thread-safe: one instance per job, used from a single job thread.
 */
public class MatchBuffer {

    public enum Side { PROD, SHADOW }

    public record Entry(LogRecord record, Signature signature) {}

    public record Pair(Entry prod, Entry shadow) {}

    private final Map<String, Entry> prod = new HashMap<>();
    private final Map<String, Entry> shadow = new HashMap<>();

    public Optional<Pair> offer(Side side, LogRecord record, Signature signature) {
        Entry entry = new Entry(record, signature);
        String traceId = record.traceId();
        Map<String, Entry> own = side == Side.PROD ? prod : shadow;
        Map<String, Entry> other = side == Side.PROD ? shadow : prod;

        Entry counterpart = other.remove(traceId);
        if (counterpart != null) {
            Pair pair = side == Side.PROD
                    ? new Pair(entry, counterpart)
                    : new Pair(counterpart, entry);
            return Optional.of(pair);
        }
        own.put(traceId, entry);
        return Optional.empty();
    }

    public Collection<Entry> remainingProd() {
        return prod.values();
    }

    public Collection<Entry> remainingShadow() {
        return shadow.values();
    }
}
