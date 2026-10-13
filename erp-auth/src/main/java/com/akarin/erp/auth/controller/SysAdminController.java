package com.akarin.erp.auth.controller;

import com.akarin.erp.auth.dto.CodeNameReq;
import com.akarin.erp.auth.dto.UserReqs;
import com.akarin.erp.auth.dto.UserView;
import com.akarin.erp.auth.entity.SysPermission;
import com.akarin.erp.auth.service.PermissionAdminService;
import com.akarin.erp.auth.service.RoleAdminService;
import com.akarin.erp.auth.service.UserAdminService;
import com.akarin.erp.common.web.R;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 用户 / 角色 / 权限点管理。读需要 sys:read，写需要 sys:write */
@RestController
@RequestMapping("/api/sys")
@RequiredArgsConstructor
public class SysAdminController {
    private static final String READ = "hasAuthority('sys:read')", WRITE = "hasAuthority('sys:write')";
    private final UserAdminService users;
    private final RoleAdminService roles;
    private final PermissionAdminService permissions;

    // ---------- users
    @GetMapping("/users") @PreAuthorize(READ)
    public R<Page<UserView>> users(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                   @RequestParam(required = false) String keyword) {
        return R.ok(users.page(page, size, keyword));
    }
    @GetMapping("/users/{id}") @PreAuthorize(READ)
    public R<UserView> user(@PathVariable Long id) { return R.ok(users.get(id)); }
    @PostMapping("/users") @PreAuthorize(WRITE)
    public R<UserView> createUser(@Valid @RequestBody UserReqs.Create req) { return R.ok(users.create(req)); }
    @PutMapping("/users/{id}") @PreAuthorize(WRITE)
    public R<UserView> updateUser(@PathVariable Long id, @Valid @RequestBody UserReqs.Update req) { return R.ok(users.update(id, req)); }
    @PutMapping("/users/{id}/password") @PreAuthorize(WRITE)
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody UserReqs.Password req) {
        users.resetPassword(id, req.password()); return R.ok();
    }
    @PutMapping("/users/{id}/status") @PreAuthorize(WRITE)
    public R<UserView> status(@PathVariable Long id, @Valid @RequestBody UserReqs.Status req) { return R.ok(users.setEnabled(id, req.enabled())); }
    @PutMapping("/users/{id}/roles") @PreAuthorize(WRITE)
    public R<UserView> assignRoles(@PathVariable Long id, @Valid @RequestBody UserReqs.Ids req) { return R.ok(users.assignRoles(id, req.ids())); }
    @DeleteMapping("/users/{id}") @PreAuthorize(WRITE)
    public R<Void> deleteUser(@PathVariable Long id) { users.delete(id); return R.ok(); }

    // ---------- roles
    @GetMapping("/roles") @PreAuthorize(READ)
    public R<List<RoleAdminService.RoleView>> roles() { return R.ok(roles.list()); }
    @GetMapping("/roles/{id}") @PreAuthorize(READ)
    public R<RoleAdminService.RoleView> role(@PathVariable Long id) { return R.ok(roles.get(id)); }
    @PostMapping("/roles") @PreAuthorize(WRITE)
    public R<RoleAdminService.RoleView> createRole(@org.springframework.validation.annotation.Validated(com.akarin.erp.common.validation.OnCreate.class) @RequestBody CodeNameReq req) { return R.ok(roles.create(req)); }
    @PutMapping("/roles/{id}") @PreAuthorize(WRITE)
    public R<RoleAdminService.RoleView> updateRole(@PathVariable Long id, @Valid @RequestBody CodeNameReq req) { return R.ok(roles.update(id, req)); }
    @PutMapping("/roles/{id}/permissions") @PreAuthorize(WRITE)
    public R<RoleAdminService.RoleView> assignPermissions(@PathVariable Long id, @Valid @RequestBody UserReqs.Ids req) {
        return R.ok(roles.assignPermissions(id, req.ids()));
    }
    @DeleteMapping("/roles/{id}") @PreAuthorize(WRITE)
    public R<Void> deleteRole(@PathVariable Long id) { roles.delete(id); return R.ok(); }

    // ---------- permissions
    @GetMapping("/permissions") @PreAuthorize(READ)
    public R<List<SysPermission>> permissions() { return R.ok(permissions.list()); }
    @PostMapping("/permissions") @PreAuthorize(WRITE)
    public R<SysPermission> createPermission(@org.springframework.validation.annotation.Validated(com.akarin.erp.common.validation.OnCreate.class) @RequestBody CodeNameReq req) { return R.ok(permissions.create(req)); }
    @PutMapping("/permissions/{id}") @PreAuthorize(WRITE)
    public R<SysPermission> updatePermission(@PathVariable Long id, @Valid @RequestBody CodeNameReq req) { return R.ok(permissions.update(id, req)); }
    @DeleteMapping("/permissions/{id}") @PreAuthorize(WRITE)
    public R<Void> deletePermission(@PathVariable Long id) { permissions.delete(id); return R.ok(); }
}
