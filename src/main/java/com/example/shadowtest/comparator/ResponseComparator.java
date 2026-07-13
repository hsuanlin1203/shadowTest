package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import java.util.Objects;

/**
 * Compares prod vs shadow responses for one product.
 * Implement as a Spring bean returning your product name from {@link #product()}.
 */
public interface ResponseComparator {

    /** Marker product used by the built-in default comparator. */
    String DEFAULT_PRODUCT = "__default__";

    /** The product this comparator handles. */
    String product();

    /** Reduce a record to a lightweight signature used for pairing/comparison. */
    Signature signature(LogRecord record);

    /** Compare two signatures. Default: exact equality. */
    default boolean matches(Signature a, Signature b) {
        return Objects.equals(a, b);
    }
}
