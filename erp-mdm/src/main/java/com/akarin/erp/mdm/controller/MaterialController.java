package com.akarin.erp.mdm.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.mdm.entity.Material;
import com.akarin.erp.mdm.service.MaterialService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
                             @RequestParam(required = false) String keyword) {
        var q = new LambdaQueryWrapper<Material>()
                .and(StringUtils.hasText(keyword), w -> w.like(Material::getCode, keyword).or().like(Material::getName, keyword))
                .orderByDesc(Material::getId);
        return R.ok(service.page(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<Material> get(@PathVariable Long id) { return R.ok(service.require(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Material> create(@RequestBody Material body) { return R.ok(service.create(body)); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> update(@PathVariable Long id, @RequestBody Material body) {
        service.require(id);
        body.setId(id);
        service.updateById(body);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> delete(@PathVariable Long id) { service.removeById(id); return R.ok(); }
}
