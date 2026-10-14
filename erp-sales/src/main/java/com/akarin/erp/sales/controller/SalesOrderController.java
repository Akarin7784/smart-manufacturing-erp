package com.akarin.erp.sales.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.sales.dto.SalesOrderCreateReq;
import com.akarin.erp.sales.dto.SalesOrderUpdateReq;
import com.akarin.erp.sales.dto.SalesOrderDetail;
import com.akarin.erp.sales.entity.SalesOrder;
import com.akarin.erp.sales.service.SalesOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sales/orders")
@RequiredArgsConstructor
public class SalesOrderController {
    private final SalesOrderService service;

    @GetMapping
    public R<Page<SalesOrder>> page(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) String status) {
        return R.ok(service.page(new Page<>(page, size), new LambdaQueryWrapper<SalesOrder>()
                .eq(status != null, SalesOrder::getStatus, status).orderByDesc(SalesOrder::getId)));
    }

    @GetMapping("/{id}")
    public R<SalesOrderDetail> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('sales:write')")
    public R<SalesOrderDetail> create(@Valid @RequestBody SalesOrderCreateReq req) { return R.ok(service.create(req)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('sales:write')")
    public R<SalesOrderDetail> update(@PathVariable Long id, @Valid @RequestBody SalesOrderUpdateReq req) {
        return R.ok(service.update(id, req));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('sales:write')")
    public R<Map<String, Long>> submit(@PathVariable Long id) {
        return R.ok(Map.of("approvalId", service.submit(id)));
    }

    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAuthority('inventory:write')")
    public R<SalesOrderDetail> ship(@PathVariable Long id) { return R.ok(service.ship(id)); }
}
