package com.akarin.erp.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

/** 草稿编辑：须带查询到的 version；只改可编辑字段，不整行写回 */
public record SalesOrderUpdateReq(
        /* 不在这里 @NotNull：服务层先判存在（404）再判 version（400） */ Integer version,
        @NotNull Long customerId, @NotNull Long warehouseId, String remark,
        @NotEmpty @Valid List<Line> items) {
    public record Line(@NotNull Long materialId,
                       @NotNull(message = "数量不能为空", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @Positive(message = "数量必须大于 0", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @com.akarin.erp.common.validation.MoneyScale(label = "数量", integer = 14, fraction = 4) BigDecimal qty,
                       @NotNull(message = "单价不能为空", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @PositiveOrZero(message = "单价不能为负数", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @com.akarin.erp.common.validation.MoneyScale(label = "单价", integer = 14, fraction = 4) BigDecimal price) {}
}
