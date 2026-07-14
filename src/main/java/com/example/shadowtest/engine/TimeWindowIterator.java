package com.example.shadowtest.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class TimeWindowIterator implements Iterator<TimeWindow> {

    private final Instant end;
    private final Duration window;
    private Instant cursor;

    public TimeWindowIterator(Instant start, Instant end, Duration window) {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.end = end;
        this.window = window;
        this.cursor = start;
    }

    @Override
    public boolean hasNext() {
        return cursor.isBefore(end);
    }

    @Override
    public TimeWindow next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Instant windowEnd = cursor.plus(window);
        if (windowEnd.isAfter(end)) {
            windowEnd = end;
        }
        TimeWindow tw = new TimeWindow(cursor, windowEnd);
        cursor = windowEnd;
        return tw;
    }

    /**
     * Returns the total number of windows this iterator will produce.
     * Must be called before any call to {@link #next()} — the count is computed
     * from the iterator's current cursor position, so calling this after
     * iteration has begun returns a smaller, incorrect count with no warning.
     */
    public int totalWindows() {
        long totalSeconds = Duration.between(cursor, end).getSeconds();
        long windowSeconds = window.getSeconds();
        return (int) ((totalSeconds + windowSeconds - 1) / windowSeconds);
    }
}
