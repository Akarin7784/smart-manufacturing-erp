package com.akarin.erp.inventory.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.inventory.entity.Stock;
import com.akarin.erp.inventory.entity.StockTxn;
import com.akarin.erp.inventory.mapper.StockMapper;
import com.akarin.erp.inventory.mapper.StockTxnMapper;
import com.akarin.erp.inventory.service.InventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {
    private final InventoryService service;
    private final StockMapper stockMapper;
    private final StockTxnMapper txnMapper;

    public record StockReq(@NotNull Long materialId, @NotNull Long warehouseId,
                           @NotNull(message = "数量不能为空") @Positive(message = "数量必须大于 0") BigDecimal qty, String remark) {}

    @GetMapping("/stocks")
    public R<Page<Stock>> stocks(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                 @RequestParam(required = false) Long materialId, @RequestParam(required = false) Long warehouseId) {
        return R.ok(stockMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<Stock>()
                .eq(materialId != null, Stock::getMaterialId, materialId)
                .eq(warehouseId != null, Stock::getWarehouseId, warehouseId)));
    }

    @GetMapping("/txns")
    public R<Page<StockTxn>> txns(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        return R.ok(txnMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<StockTxn>().orderByDesc(StockTxn::getId)));
    }

    /** 手工入库（其他入库） */
    @PostMapping("/in")
    @PreAuthorize("hasAuthority('inventory:write')")
    public R<StockTxn> in(@Valid @RequestBody StockReq r) {
        return R.ok(service.stockIn(r.materialId(), r.warehouseId(), r.qty(), "MANUAL", null, r.remark()));
    }

    /** 手工出库（其他出库） */
    @PostMapping("/out")
    @PreAuthorize("hasAuthority('inventory:write')")
    public R<StockTxn> out(@Valid @RequestBody StockReq r) {
        return R.ok(service.stockOut(r.materialId(), r.warehouseId(), r.qty(), "MANUAL", null, r.remark()));
    }
}
