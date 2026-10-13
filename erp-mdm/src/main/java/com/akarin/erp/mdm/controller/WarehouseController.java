package com.akarin.erp.mdm.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.mdm.dto.WarehouseReq;
import com.akarin.erp.mdm.entity.Warehouse;
import com.akarin.erp.mdm.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mdm/warehouses")
@RequiredArgsConstructor
public class WarehouseController {
    private final WarehouseService service;

    @GetMapping
    public R<Page<Warehouse>> page(@RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "20") long size,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer status) {
        var q = new LambdaQueryWrapper<Warehouse>()
                .and(StringUtils.hasText(keyword), w -> w.like(Warehouse::getCode, keyword).or().like(Warehouse::getName, keyword))
                .eq(status != null, Warehouse::getStatus, status)
                .orderByDesc(Warehouse::getId);
        return R.ok(service.page(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<Warehouse> get(@PathVariable Long id) { return R.ok(service.require(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Warehouse> create(@Valid @RequestBody WarehouseReq body) { return R.ok(service.create(body.toEntity())); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Warehouse> update(@PathVariable Long id, @Valid @RequestBody WarehouseReq body) { return R.ok(service.update(id, body.toEntity())); }

    /** 逻辑删除；被单据引用时返回 409/40901，请改为停用（PUT status=0） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
