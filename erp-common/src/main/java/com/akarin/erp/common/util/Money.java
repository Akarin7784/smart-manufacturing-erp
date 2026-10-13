package com.akarin.erp.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/** 金额计算：统一 BigDecimal、2 位小数、四舍五入（HALF_UP） */
public final class Money {
    public static final int SCALE = 2;
    private Money() {}

    public static BigDecimal of(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** 行金额 = 数量 × 单价 */
    public static BigDecimal lineAmount(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) throw new IllegalArgumentException("qty/price 不能为空");
        return of(qty.multiply(price));
    }

    public static BigDecimal sum(Collection<BigDecimal> values) {
        return of(values.stream().map(Money::of).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
