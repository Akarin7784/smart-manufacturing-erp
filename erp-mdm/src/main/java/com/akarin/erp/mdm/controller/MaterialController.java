package com.akarin.erp.mdm.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.mdm.dto.MaterialReq;
import com.akarin.erp.mdm.entity.Material;
import com.akarin.erp.mdm.service.MaterialService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mdm/materials")
@RequiredArgsConstructor
public class MaterialController {
    private final MaterialService service;

    @GetMapping
    public R<Page<Material>> page(@RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "20") long size,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer status) {
        var q = new LambdaQueryWrapper<Material>()
                .and(StringUtils.hasText(keyword), w -> w.like(Material::getCode, keyword).or().like(Material::getName, keyword))
                .eq(status != null, Material::getStatus, status)
                .orderByDesc(Material::getId);
        return R.ok(service.page(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<Material> get(@PathVariable Long id) { return R.ok(service.require(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Material> create(@Valid @RequestBody MaterialReq body) { return R.ok(service.create(body.toEntity())); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Material> update(@PathVariable Long id, @Valid @RequestBody MaterialReq body) { return R.ok(service.update(id, body.toEntity())); }

    /** 逻辑删除；被单据引用时返回 409/40901，请改为停用（PUT status=0） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
