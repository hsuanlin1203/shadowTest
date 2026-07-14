package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ComparatorRegistryTest {

    static class LenientComparator implements ResponseComparator {
        @Override public String product() { return "lenient"; }
        @Override public Signature signature(LogRecord r) { return new Signature("const"); }
    }

    @Test
    void returnsProductSpecificComparator() {
        HashResponseComparator def = new HashResponseComparator();
        LenientComparator lenient = new LenientComparator();
        ComparatorRegistry registry = new ComparatorRegistry(List.of(def, lenient), def);

        assertThat(registry.forProduct("lenient")).isSameAs(lenient);
    }

    @Test
    void fallsBackToDefaultForUnknownProduct() {
        HashResponseComparator def = new HashResponseComparator();
        ComparatorRegistry registry = new ComparatorRegistry(List.of(def), def);

        assertThat(registry.forProduct("unknown")).isSameAs(def);
    }

    @Test
    void defaultMarkerIsNotRegisteredAsProduct() {
        HashResponseComparator def = new HashResponseComparator();
        ComparatorRegistry registry = new ComparatorRegistry(List.of(def), def);
        // "__default__" should resolve to the default, not be treated as a real product key
        assertThat(registry.forProduct(ResponseComparator.DEFAULT_PRODUCT)).isSameAs(def);
    }
}
