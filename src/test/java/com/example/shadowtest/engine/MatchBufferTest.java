package com.example.shadowtest.engine;

import com.example.shadowtest.comparator.Signature;
import com.example.shadowtest.engine.MatchBuffer.Pair;
import com.example.shadowtest.engine.MatchBuffer.Side;
import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MatchBufferTest {

    private LogRecord rec(String traceId, String body) {
        return new LogRecord(traceId, "h", Instant.EPOCH, "doc-" + traceId, "i", body, "demo");
    }

    private Signature sig(String v) {
        return new Signature(v);
    }

    @Test
    void firstSideBuffersAndReturnsEmpty() {
        MatchBuffer buffer = new MatchBuffer();
        Optional<Pair> result = buffer.offer(Side.PROD, rec("t1", "a"), sig("h1"));
        assertThat(result).isEmpty();
        assertThat(buffer.remainingProd()).hasSize(1);
        assertThat(buffer.remainingShadow()).isEmpty();
    }

    @Test
    void oppositeSideSameTraceIdPairsAndClears() {
        MatchBuffer buffer = new MatchBuffer();
        buffer.offer(Side.PROD, rec("t1", "a"), sig("h1"));
        Optional<Pair> result = buffer.offer(Side.SHADOW, rec("t1", "a"), sig("h1"));

        assertThat(result).isPresent();
        assertThat(result.get().prod().record().traceId()).isEqualTo("t1");
        assertThat(result.get().shadow().record().traceId()).isEqualTo("t1");
        assertThat(result.get().prod().signature()).isEqualTo(sig("h1"));
        assertThat(buffer.remainingProd()).isEmpty();
        assertThat(buffer.remainingShadow()).isEmpty();
    }

    @Test
    void crossWindowPairingKeepsFullRecords() {
        // prod arrives in an early "window", shadow much later — both bodies retained
        MatchBuffer buffer = new MatchBuffer();
        buffer.offer(Side.PROD, rec("t1", "prod-body"), sig("h1"));
        // ... many other offers for other traceIds could happen here ...
        buffer.offer(Side.PROD, rec("t2", "x"), sig("hx"));
        Optional<Pair> result = buffer.offer(Side.SHADOW, rec("t1", "shadow-body"), sig("h2"));

        assertThat(result).isPresent();
        assertThat(result.get().prod().record().rawBody()).isEqualTo("prod-body");
        assertThat(result.get().shadow().record().rawBody()).isEqualTo("shadow-body");
        assertThat(buffer.remainingProd()).extracting(e -> e.record().traceId()).containsExactly("t2");
    }

    @Test
    void unmatchedRemainOnBothSides() {
        MatchBuffer buffer = new MatchBuffer();
        buffer.offer(Side.PROD, rec("only-prod", "a"), sig("h1"));
        buffer.offer(Side.SHADOW, rec("only-shadow", "b"), sig("h2"));

        assertThat(buffer.remainingProd()).extracting(e -> e.record().traceId()).containsExactly("only-prod");
        assertThat(buffer.remainingShadow()).extracting(e -> e.record().traceId()).containsExactly("only-shadow");
    }
}
