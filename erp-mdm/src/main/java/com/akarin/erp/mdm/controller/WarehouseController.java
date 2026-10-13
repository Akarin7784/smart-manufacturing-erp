package com.akarin.erp.mdm.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.mdm.entity.Warehouse;
import com.akarin.erp.mdm.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
                             @RequestParam(required = false) String keyword) {
        var q = new LambdaQueryWrapper<Warehouse>()
                .and(StringUtils.hasText(keyword), w -> w.like(Warehouse::getCode, keyword).or().like(Warehouse::getName, keyword))
                .orderByDesc(Warehouse::getId);
        return R.ok(service.page(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<Warehouse> get(@PathVariable Long id) { return R.ok(service.require(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Warehouse> create(@RequestBody Warehouse body) { return R.ok(service.create(body)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> update(@PathVariable Long id, @RequestBody Warehouse body) {
        service.require(id);
        body.setId(id);
        service.updateById(body);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> delete(@PathVariable Long id) { service.removeById(id); return R.ok(); }
}
