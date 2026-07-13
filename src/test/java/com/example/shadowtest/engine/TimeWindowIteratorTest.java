package com.example.shadowtest.engine;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowIteratorTest {

    private static final Duration TEN_MIN = Duration.ofMinutes(10);

    @Test
    void splitsRangeIntoTenMinuteWindows() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:30:00Z");
        TimeWindowIterator it = new TimeWindowIterator(start, end, TEN_MIN);

        List<TimeWindow> windows = new ArrayList<>();
        it.forEachRemaining(windows::add);

        assertThat(windows).containsExactly(
            new TimeWindow(Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:10:00Z")),
            new TimeWindow(Instant.parse("2026-07-13T00:10:00Z"), Instant.parse("2026-07-13T00:20:00Z")),
            new TimeWindow(Instant.parse("2026-07-13T00:20:00Z"), Instant.parse("2026-07-13T00:30:00Z"))
        );
    }

    @Test
    void lastWindowIsClampedToEnd() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:25:00Z");
        TimeWindowIterator it = new TimeWindowIterator(start, end, TEN_MIN);

        List<TimeWindow> windows = new ArrayList<>();
        it.forEachRemaining(windows::add);

        assertThat(windows).hasSize(3);
        assertThat(windows.get(2)).isEqualTo(
            new TimeWindow(Instant.parse("2026-07-13T00:20:00Z"), Instant.parse("2026-07-13T00:25:00Z")));
    }

    @Test
    void totalWindowsCountedUpFront() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:25:00Z");
        assertThat(new TimeWindowIterator(start, end, TEN_MIN).totalWindows()).isEqualTo(3);
    }

    @Test
    void singleShortRangeIsOneWindow() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:03:00Z");
        TimeWindowIterator it = new TimeWindowIterator(start, end, TEN_MIN);
        List<TimeWindow> windows = new ArrayList<>();
        it.forEachRemaining(windows::add);
        assertThat(windows).containsExactly(new TimeWindow(start, end));
    }

    @Test
    void throwsIllegalArgumentWhenEndNotAfterStart() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:00:00Z");
        assertThatThrownBy(() -> new TimeWindowIterator(start, end, TEN_MIN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsIllegalArgumentWhenEndBeforeStart() {
        Instant start = Instant.parse("2026-07-13T00:10:00Z");
        Instant end = Instant.parse("2026-07-13T00:00:00Z");
        assertThatThrownBy(() -> new TimeWindowIterator(start, end, TEN_MIN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsIllegalArgumentWhenWindowIsZero() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:10:00Z");
        assertThatThrownBy(() -> new TimeWindowIterator(start, end, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsIllegalArgumentWhenWindowIsNegative() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:10:00Z");
        assertThatThrownBy(() -> new TimeWindowIterator(start, end, Duration.ofMinutes(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
