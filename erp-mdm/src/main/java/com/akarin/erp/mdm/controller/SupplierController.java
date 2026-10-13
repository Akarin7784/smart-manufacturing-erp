package com.akarin.erp.mdm.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.mdm.dto.SupplierReq;
import com.akarin.erp.mdm.entity.Supplier;
import com.akarin.erp.mdm.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.akarin.erp.common.validation.OnCreate;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
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
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer status) {
        var q = new LambdaQueryWrapper<Supplier>()
                .and(StringUtils.hasText(keyword), w -> w.like(Supplier::getCode, keyword).or().like(Supplier::getName, keyword))
                .eq(status != null, Supplier::getStatus, status)
                .orderByDesc(Supplier::getId);
        return R.ok(service.page(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<Supplier> get(@PathVariable Long id) { return R.ok(service.require(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Supplier> create(@Validated(OnCreate.class) @RequestBody SupplierReq body) { return R.ok(service.create(body.toEntity())); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Supplier> update(@PathVariable Long id, @Valid @RequestBody SupplierReq body) { return R.ok(service.update(id, body.toEntity())); }

    /** 逻辑删除；被单据引用时返回 409/40901，请改为停用（PUT status=0） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
