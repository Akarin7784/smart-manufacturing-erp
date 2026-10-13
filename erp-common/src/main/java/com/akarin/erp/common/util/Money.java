package com.akarin.erp.common.util;

import com.akarin.erp.common.exception.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/** 金额计算：统一 BigDecimal、2 位小数。计算结果（如数量×单价）按 HALF_UP 保留 2 位；外部传入的金额参数用 requireAmount 校验，超过 2 位小数直接拒绝 */
public final class Money {
    public static final int SCALE = 2;
    private Money() {}

    public static BigDecimal of(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 校验金额参数：不能为空、最多 2 位小数（超过直接拒绝，不四舍五入）、≥ 0.01。返回 2 位小数的值。
     * @param label 提示里的字段名，如"核销金额"
     */
    public static BigDecimal requireAmount(BigDecimal v, String label) {
        if (v == null) throw new BizException(label + "不能为空");
        if (v.stripTrailingZeros().scale() > SCALE) throw new BizException(label + "最多 2 位小数");
        if (v.compareTo(new BigDecimal("0.01")) < 0) throw new BizException(label + "必须 ≥ 0.01");
        return v.setScale(SCALE, RoundingMode.UNNECESSARY);
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
