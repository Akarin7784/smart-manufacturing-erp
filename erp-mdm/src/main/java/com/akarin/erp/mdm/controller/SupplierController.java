package com.akarin.erp.mdm.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.mdm.entity.Supplier;
import com.akarin.erp.mdm.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mdm/suppliers")
@RequiredArgsConstructor
public class SupplierController {
    private final SupplierService service;

    @GetMapping
    public R<Page<Supplier>> page(@RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "20") long size,
                             @RequestParam(required = false) String keyword) {
        var q = new LambdaQueryWrapper<Supplier>()
                .and(StringUtils.hasText(keyword), w -> w.like(Supplier::getCode, keyword).or().like(Supplier::getName, keyword))
                .orderByDesc(Supplier::getId);
        return R.ok(service.page(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<Supplier> get(@PathVariable Long id) { return R.ok(service.require(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Supplier> create(@RequestBody Supplier body) { return R.ok(service.create(body)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> update(@PathVariable Long id, @RequestBody Supplier body) {
        service.require(id);
        body.setId(id);
        service.updateById(body);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> delete(@PathVariable Long id) { service.removeById(id); return R.ok(); }
}
