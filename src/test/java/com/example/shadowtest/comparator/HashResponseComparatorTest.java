package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class HashResponseComparatorTest {

    private final HashResponseComparator comparator = new HashResponseComparator();

    private LogRecord record(String body) {
        return new LogRecord("t1", "h", Instant.EPOCH, "d", "i", body, "demo");
    }

    @Test
    void identicalBodiesProduceEqualSignatures() {
        Signature a = comparator.signature(record("hello"));
        Signature b = comparator.signature(record("hello"));
        assertThat(a).isEqualTo(b);
        assertThat(comparator.matches(a, b)).isTrue();
    }

    @Test
    void differentBodiesProduceDifferentSignatures() {
        Signature a = comparator.signature(record("hello"));
        Signature b = comparator.signature(record("world"));
        assertThat(a).isNotEqualTo(b);
        assertThat(comparator.matches(a, b)).isFalse();
    }

    @Test
    void signatureIsStableSha256Hex() {
        // sha256("hello") known value
        assertThat(comparator.signature(record("hello")).value())
            .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void nullBodyIsHandled() {
        Signature a = comparator.signature(record(null));
        Signature b = comparator.signature(record(null));
        assertThat(comparator.matches(a, b)).isTrue();
    }

    @Test
    void defaultProductMarker() {
        assertThat(comparator.product()).isEqualTo(ResponseComparator.DEFAULT_PRODUCT);
    }
}
