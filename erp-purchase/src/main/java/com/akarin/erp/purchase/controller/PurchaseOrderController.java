package com.akarin.erp.purchase.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.purchase.dto.PurchaseOrderCreateReq;
import com.akarin.erp.purchase.dto.PurchaseOrderDetail;
import com.akarin.erp.purchase.entity.PurchaseOrder;
import com.akarin.erp.purchase.service.PurchaseOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/purchase/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {
    private final PurchaseOrderService service;

    @GetMapping
    public R<Page<PurchaseOrder>> page(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                    @RequestParam(required = false) String status) {
        return R.ok(service.page(new Page<>(page, size), new LambdaQueryWrapper<PurchaseOrder>()
                .eq(status != null, PurchaseOrder::getStatus, status).orderByDesc(PurchaseOrder::getId)));
    }

    @GetMapping("/{id}")
    public R<PurchaseOrderDetail> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:write')")
    public R<PurchaseOrderDetail> create(@Valid @RequestBody PurchaseOrderCreateReq req) { return R.ok(service.create(req)); }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('purchase:write')")
    public R<Map<String, Long>> submit(@PathVariable Long id) {
        return R.ok(Map.of("approvalId", service.submit(id)));
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('inventory:write')")
    public R<PurchaseOrderDetail> receive(@PathVariable Long id) { return R.ok(service.receive(id)); }
}
