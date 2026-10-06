package com.example.item;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItemSearchConditionTest {

    @Test
    @DisplayName("phpIntval: long 최댓값 · 최솟값과 그 직전 값은 정밀도 손실 없이 그대로 읽는다")
    void keepsExactValuesAtLongBounds() {
        assertThat(ItemSearchCondition.phpIntval("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntval("9223372036854775806")).isEqualTo(Long.MAX_VALUE - 1);
        assertThat(ItemSearchCondition.phpIntval("-9223372036854775808")).isEqualTo(Long.MIN_VALUE);
        assertThat(ItemSearchCondition.phpIntval("-9223372036854775807")).isEqualTo(Long.MIN_VALUE + 1);
        assertThat(ItemSearchCondition.phpIntval("+9223372036854775807")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("phpIntval: long 범위를 1 넘으면 최댓값 · 최솟값으로 붙인다")
    void capsJustBeyondLongBounds() {
        assertThat(ItemSearchCondition.phpIntval("9223372036854775808")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntval("-9223372036854775809")).isEqualTo(Long.MIN_VALUE);
        assertThat(ItemSearchCondition.phpIntval("99999999999999999999")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntval("-99999999999999999999")).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    @DisplayName("phpIntval: 부호 · 앞 0 · 뒤따르는 문자 처리")
    void handlesSignsAndTrailingText() {
        assertThat(ItemSearchCondition.phpIntval("-0")).isZero();
        assertThat(ItemSearchCondition.phpIntval("007")).isEqualTo(7L);
        assertThat(ItemSearchCondition.phpIntval("-12abc")).isEqualTo(-12L);
        assertThat(ItemSearchCondition.phpIntval("+")).isZero();
        assertThat(ItemSearchCondition.phpIntval("")).isZero();
        assertThat(ItemSearchCondition.phpIntval("  42")).isEqualTo(42L);
    }

    @Test
    @DisplayName("phpIntval: 소수는 0 쪽으로 버리고 지수는 double 로 읽는다")
    void truncatesDecimalsAndReadsExponents() {
        assertThat(ItemSearchCondition.phpIntval("2.7")).isEqualTo(2L);
        assertThat(ItemSearchCondition.phpIntval("-2.7")).isEqualTo(-2L);
        assertThat(ItemSearchCondition.phpIntval(".5")).isZero();
        assertThat(ItemSearchCondition.phpIntval("1e3")).isEqualTo(1000L);
        assertThat(ItemSearchCondition.phpIntval("1E3")).isEqualTo(1000L);
        assertThat(ItemSearchCondition.phpIntval("-1e3")).isEqualTo(-1000L);
        assertThat(ItemSearchCondition.phpIntval("1e-3")).isZero();
        assertThat(ItemSearchCondition.phpIntval("1.5e1")).isEqualTo(15L);
        assertThat(ItemSearchCondition.phpIntval("1e18")).isEqualTo(1_000_000_000_000_000_000L);
    }

    @Test
    @DisplayName("phpIntval: 지수 표기로 long 범위를 넘으면 붙이고, 무한대는 0")
    void capsExponentOverflowAndZeroesInfinity() {
        assertThat(ItemSearchCondition.phpIntval("9.3e18")).isEqualTo(Long.MAX_VALUE);
        assertThat(ItemSearchCondition.phpIntval("-9.3e18")).isEqualTo(Long.MIN_VALUE);
        assertThat(ItemSearchCondition.phpIntval("1e999")).isZero();
        assertThat(ItemSearchCondition.phpIntval("-1e999")).isZero();
    }
}
