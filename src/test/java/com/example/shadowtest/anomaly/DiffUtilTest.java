package com.example.shadowtest.anomaly;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DiffUtilTest {

    @Test
    void equalStringsProduceEmptyDiff() {
        assertThat(DiffUtil.lineDiff("same", "same")).isEmpty();
    }

    @Test
    void differingLinesAreMarked() {
        String diff = DiffUtil.lineDiff("a\nb\nc", "a\nX\nc");
        assertThat(diff).contains("- b");
        assertThat(diff).contains("+ X");
        assertThat(diff).doesNotContain("- a");
    }

    @Test
    void handlesNullsAsEmpty() {
        String diff = DiffUtil.lineDiff(null, "x");
        assertThat(diff).contains("+ x");
    }

    @Test
    void extraLinesOnOneSideAreMarked() {
        String diff = DiffUtil.lineDiff("a", "a\nb");
        assertThat(diff).contains("+ b");
    }
}
