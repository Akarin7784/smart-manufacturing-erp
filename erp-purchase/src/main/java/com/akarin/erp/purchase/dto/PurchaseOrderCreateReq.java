package com.akarin.erp.purchase.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public record PurchaseOrderCreateReq(@NotNull Long supplierId, @NotNull Long warehouseId, String remark,
                                  @NotEmpty @Valid List<Line> items) {
    public record Line(@NotNull Long materialId, @NotNull(message = "数量不能为空") @Positive(message = "数量必须大于 0") BigDecimal qty, @NotNull(message = "单价不能为空") @PositiveOrZero(message = "单价不能为负数") BigDecimal price) {}
}
