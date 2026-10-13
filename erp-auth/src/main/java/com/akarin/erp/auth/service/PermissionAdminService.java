package com.akarin.erp.auth.service;

import com.akarin.erp.auth.dto.CodeNameReq;
import com.akarin.erp.auth.entity.SysPermission;
import com.akarin.erp.auth.entity.SysRolePermission;
import com.akarin.erp.auth.mapper.SysPermissionMapper;
import com.akarin.erp.auth.mapper.SysRolePermissionMapper;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PermissionAdminService {
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserMapper userMapper;

    public List<SysPermission> list() {
        return permissionMapper.selectList(new LambdaQueryWrapper<SysPermission>().orderByAsc(SysPermission::getId));
    }

    @Transactional
    public SysPermission create(CodeNameReq req) {
        if (permissionMapper.countByCodeIncludingDeleted(req.code()) > 0)
            throw new BizException(ErrorCode.CODE_DUPLICATED, "编码已存在（含已删除）: " + req.code());
        SysPermission p = new SysPermission();
        p.setCode(req.code()); p.setName(req.name());
        permissionMapper.insert(p);
        return p;
    }

    @Transactional
    public SysPermission update(Long id, CodeNameReq req) {
        SysPermission p = require(id);
        if (!p.getCode().equals(req.code())) throw new BizException("权限点编码不可修改");
        p.setName(req.name());
        permissionMapper.updateById(p);
        return p;
    }

    @Transactional
    public void delete(Long id) {
        SysPermission p = require(id);
        if (BuiltinPermissions.CODES.contains(p.getCode())) throw new BizException("内置权限点不能删除: " + p.getCode());
        List<Long> roleIds = rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getPermissionId, id)).stream().map(SysRolePermission::getRoleId).toList();
        roleIds.forEach(userMapper::bumpTokenVersionByRole);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getPermissionId, id));
        permissionMapper.deleteById(id);
    }

    private SysPermission require(Long id) {
        SysPermission p = permissionMapper.selectById(id);
        if (p == null) throw new BizException(ErrorCode.NOT_FOUND, "权限点不存在: " + id);
        return p;
    }
}
