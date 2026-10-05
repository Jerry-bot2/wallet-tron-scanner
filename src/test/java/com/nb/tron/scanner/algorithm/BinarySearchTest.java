package com.nb.tron.scanner.algorithm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 05.10.26
 */
class BinarySearchTest {

    @ParameterizedTest
    @ValueSource(longs = {1000, 1001, 1007, 1009})
    void shouldFindBoundaryIncludingBothEnds(long lastMatch) {
        long result = BinarySearch.findLastMatch(1000, 1010, height -> height <= lastMatch);
        assertThat(result).isEqualTo(lastMatch);
    }

    @Test
    void shouldReturnLeftWhenBoundsAreAdjacentWithoutCallingPredicate() {
        long result = BinarySearch.findLastMatch(1000, 1001, height -> {
            throw new AssertionError("已确认的边界不需要重复判断");
        });
        assertThat(result).isEqualTo(1000);
    }

    @Test
    void shouldSupportGenesisSentinelAsLeftBoundary() {
        assertThat(BinarySearch.findLastMatch(-1, 10, height -> height < 0)).isEqualTo(-1);
    }

    @Test
    void shouldKeepComparisonCountLogarithmicForLargeRange() {
        AtomicInteger comparisons = new AtomicInteger();
        long result = BinarySearch.findLastMatch(0, 10_000_000_000L, height -> {
            comparisons.incrementAndGet();
            return height <= 7_000_000_000L;
        });
        assertThat(result).isEqualTo(7_000_000_000L);
        assertThat(comparisons.get()).isLessThanOrEqualTo(34);
    }

    @Test
    void shouldPropagateComparisonFailure() {
        IllegalStateException failure = new IllegalStateException("节点读取失败");
        assertThatThrownBy(() -> BinarySearch.findLastMatch(1000, 1010, height -> {
            throw failure;
        })).isSameAs(failure);
    }
}
