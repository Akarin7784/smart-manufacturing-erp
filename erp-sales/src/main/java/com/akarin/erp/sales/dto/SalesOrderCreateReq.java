package com.akarin.erp.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public record SalesOrderCreateReq(@NotNull Long customerId, @NotNull Long warehouseId, String remark,
                                  @NotEmpty @Valid List<Line> items) {
    public record Line(@NotNull Long materialId, @NotNull @Positive BigDecimal qty, @NotNull @PositiveOrZero BigDecimal price) {}
}
