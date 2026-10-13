package com.akarin.erp.common.util;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MoneyTest {
    @Test void lineAmountIsQtyTimesPriceRoundedHalfUp() {
        assertThat(Money.lineAmount(new BigDecimal("3"), new BigDecimal("0.335"))).isEqualByComparingTo("1.01");
        assertThat(Money.lineAmount(new BigDecimal("100"), new BigDecimal("0.5"))).isEqualTo(new BigDecimal("50.00"));
        assertThat(Money.lineAmount(new BigDecimal("0.1"), new BigDecimal("0.2"))).isEqualTo(new BigDecimal("0.02"));
    }
    @Test void lineAmountRejectsNull() {
        assertThatThrownBy(() -> Money.lineAmount(null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.lineAmount(BigDecimal.ONE, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sumAndNullSafety() {
        assertThat(Money.sum(List.of(new BigDecimal("1.005"), new BigDecimal("2")))).isEqualTo(new BigDecimal("3.01"));
        assertThat(Money.of(null)).isEqualTo(new BigDecimal("0.00"));
    }

    @org.junit.jupiter.api.Test void requireAmountRejectsInsteadOfRounding() {
        assertThat(Money.requireAmount(new BigDecimal("1.5"), "金额")).isEqualTo(new BigDecimal("1.50"));
        assertThat(Money.requireAmount(new BigDecimal("1.230"), "金额")).as("末尾 0 不算多余小数").isEqualTo(new BigDecimal("1.23"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Money.requireAmount(new BigDecimal("1.005"), "核销金额")).hasMessage("核销金额最多 2 位小数");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Money.requireAmount(new BigDecimal("0.004"), "核销金额")).hasMessage("核销金额最多 2 位小数");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Money.requireAmount(BigDecimal.ZERO, "核销金额")).hasMessage("核销金额必须 ≥ 0.01");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Money.requireAmount(null, "核销金额")).hasMessage("核销金额不能为空");
    }
}
