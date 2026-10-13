package com.akarin.erp.auth.service;

import com.akarin.erp.auth.dto.CodeNameReq;
import com.akarin.erp.auth.entity.SysPermission;
import com.akarin.erp.auth.entity.SysRole;
import com.akarin.erp.auth.entity.SysRolePermission;
import com.akarin.erp.auth.entity.SysUserRole;
import com.akarin.erp.auth.mapper.SysPermissionMapper;
import com.akarin.erp.auth.mapper.SysRoleMapper;
import com.akarin.erp.auth.mapper.SysRolePermissionMapper;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.auth.mapper.SysUserRoleMapper;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleAdminService {
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;

    public record RoleView(Long id, String code, String name, List<Long> permissionIds) {}

    public List<RoleView> list() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId)).stream().map(this::view).toList();
    }

    public RoleView get(Long id) { return view(require(id)); }

    @Transactional
    public RoleView create(CodeNameReq req) {
        if (roleMapper.countByCodeIncludingDeleted(req.code()) > 0)
            throw new BizException(ErrorCode.CODE_DUPLICATED, "编码已存在（含已删除）: " + req.code());
        SysRole r = new SysRole();
        r.setCode(req.code()); r.setName(req.name());
        roleMapper.insert(r);
        return view(r);
    }

    @Transactional
    public RoleView update(Long id, CodeNameReq req) {
        SysRole r = require(id);
        if (!r.getCode().equals(req.code())) throw new BizException("角色编码不可修改");
        r.setName(req.name());
        roleMapper.updateById(r);
        return view(r);
    }

    @Transactional
    public void delete(Long id) {
        SysRole r = require(id);
        if (UserAdminService.ADMIN_ROLE.equals(r.getCode())) throw new BizException("不能删除内置管理员角色");
        userMapper.bumpTokenVersionByRole(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id));
        roleMapper.deleteById(id);
    }

    /** 覆盖式分配权限点；持有该角色的用户需重新登录 */
    @Transactional
    public RoleView assignPermissions(Long id, List<Long> permissionIds) {
        SysRole r = require(id);
        var ids = new LinkedHashSet<>(permissionIds);
        List<SysPermission> perms = ids.isEmpty() ? List.of() : permissionMapper.selectBatchIds(ids);
        if (perms.size() != ids.size()) throw new BizException(ErrorCode.NOT_FOUND, "权限点不存在");
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getRoleId, id));
        ids.forEach(pid -> rolePermissionMapper.insert(new SysRolePermission(id, pid)));
        userMapper.bumpTokenVersionByRole(id);
        return view(r);
    }

    private SysRole require(Long id) {
        SysRole r = roleMapper.selectById(id);
        if (r == null) throw new BizException(ErrorCode.NOT_FOUND, "角色不存在: " + id);
        return r;
    }

    private RoleView view(SysRole r) {
        List<Long> pids = rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getRoleId, r.getId())).stream().map(SysRolePermission::getPermissionId).sorted().toList();
        return new RoleView(r.getId(), r.getCode(), r.getName(), pids);
    }
}
