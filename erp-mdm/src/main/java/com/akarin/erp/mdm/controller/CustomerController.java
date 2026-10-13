package com.akarin.erp.mdm.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.mdm.dto.CustomerReq;
import com.akarin.erp.mdm.entity.Customer;
import com.akarin.erp.mdm.service.CustomerService;
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
@RequestMapping("/api/mdm/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService service;

    @GetMapping
    public R<Page<Customer>> page(@RequestParam(defaultValue = "1") long page,
                             @RequestParam(defaultValue = "20") long size,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Integer status) {
        var q = new LambdaQueryWrapper<Customer>()
                .and(StringUtils.hasText(keyword), w -> w.like(Customer::getCode, keyword).or().like(Customer::getName, keyword))
                .eq(status != null, Customer::getStatus, status)
                .orderByDesc(Customer::getId);
        return R.ok(service.page(new Page<>(page, size), q));
    }

    @GetMapping("/{id}")
    public R<Customer> get(@PathVariable Long id) { return R.ok(service.require(id)); }

    @PostMapping
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Customer> create(@Validated(OnCreate.class) @RequestBody CustomerReq body) { return R.ok(service.create(body.toEntity())); }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Customer> update(@PathVariable Long id, @Valid @RequestBody CustomerReq body) { return R.ok(service.update(id, body.toEntity())); }

    /** 逻辑删除；被单据引用时返回 409/40901，请改为停用（PUT status=0） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('mdm:write')")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }
}
