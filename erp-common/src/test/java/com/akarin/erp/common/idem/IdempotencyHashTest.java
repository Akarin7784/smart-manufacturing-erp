package com.akarin.erp.common.idem;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyHashTest {
    record Line(Long materialId, BigDecimal qty) {}
    record Req(Long supplierId, String remark, List<Line> items) {}

    @Test void canonicalHashIgnoresTrailingZerosAndMapOrder() {
        assertThat(IdempotencyService.hash("op", new Req(1L, "r", List.of(new Line(2L, new BigDecimal("1.0"))))))
                .isEqualTo(IdempotencyService.hash("op", new Req(1L, "r", List.of(new Line(2L, new BigDecimal("1"))))));
        assertThat(IdempotencyService.hash("op", new Line(1L, new BigDecimal("0.00")))).isEqualTo(IdempotencyService.hash("op", new Line(1L, BigDecimal.ZERO)));
        Map<String, Object> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        a.put("x", 1); a.put("y", 2); b.put("y", 2); b.put("x", 1);
        assertThat(IdempotencyService.hash("op", a)).isEqualTo(IdempotencyService.hash("op", b));
    }

    @Test void differentValuesOrOpDiffer() {
        Line l = new Line(2L, new BigDecimal("1"));
        assertThat(IdempotencyService.hash("op", l)).isNotEqualTo(IdempotencyService.hash("op", new Line(2L, new BigDecimal("1.01"))));
        assertThat(IdempotencyService.hash("op", l)).isNotEqualTo(IdempotencyService.hash("op2", l));
    }
}
