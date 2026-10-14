package com.akarin.erp.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

/** 草稿编辑：须带查询到的 version；只改可编辑字段，不整行写回 */
public record PurchaseOrderUpdateReq(
        @NotNull(message = "version 不能为空（请先查询详情拿到最新 version）",
                payload = com.akarin.erp.common.validation.FullMessage.class) Integer version,
        @NotNull Long supplierId, @NotNull Long warehouseId, String remark,
        @NotEmpty @Valid List<Line> items) {
    public record Line(@NotNull Long materialId,
                       @NotNull(message = "数量不能为空", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @Positive(message = "数量必须大于 0", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @com.akarin.erp.common.validation.MoneyScale(label = "数量", integer = 14, fraction = 4) BigDecimal qty,
                       @NotNull(message = "单价不能为空", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @PositiveOrZero(message = "单价不能为负数", payload = com.akarin.erp.common.validation.FullMessage.class)
                       @com.akarin.erp.common.validation.MoneyScale(label = "单价", integer = 14, fraction = 4) BigDecimal price) {}
}
