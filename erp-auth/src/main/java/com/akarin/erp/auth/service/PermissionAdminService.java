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
        p.setCode(req.code()); p.setName(req.name()); p.setVersion(0);
        try {
            permissionMapper.insert(p);
        } catch (org.springframework.dao.DuplicateKeyException race) {   // 并发同编码创建
            throw new BizException(ErrorCode.CODE_DUPLICATED, "编码已存在: " + req.code());
        }
        return p;
    }

    @Transactional
    public SysPermission update(Long id, CodeNameReq req) {
        SysPermission p = require(id);
        if (req.code() != null && !p.getCode().equals(req.code())) throw new BizException("权限点编码不可修改");   // 不传 = 不改
        com.akarin.erp.common.util.Versions.requirePresent(req.version());
        // 只更新可编辑字段（name），WHERE id + 客户端 version，不整行写回
        com.akarin.erp.common.util.Versions.requireOneRow(permissionMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysPermission>()
                        .eq(SysPermission::getId, id).eq(SysPermission::getVersion, req.version())
                        .set(SysPermission::getName, req.name())
                        .set(SysPermission::getUpdatedAt, java.time.LocalDateTime.now())
                        .setSql("version = version + 1")));
        return require(id);
    }

    @Transactional
    public void delete(Long id) {
        SysPermission p = require(id);
        if (BuiltinPermissions.CODES.contains(p.getCode())) throw new BizException("内置权限点不能删除: " + p.getCode());
        List<Long> roleIds = rolePermissionMapper.selectList(new LambdaQueryWrapper<SysRolePermission>()
                .eq(SysRolePermission::getPermissionId, id)).stream().map(SysRolePermission::getRoleId).toList();
        roleIds.forEach(userMapper::bumpTokenVersionByRole);
        rolePermissionMapper.delete(new LambdaQueryWrapper<SysRolePermission>().eq(SysRolePermission::getPermissionId, id));
        com.akarin.erp.common.util.Versions.requireOneRow(permissionMapper.deleteById(id));   // 按 id 软删，不带 version 条件
    }

    private SysPermission require(Long id) {
        SysPermission p = permissionMapper.selectById(id);
        if (p == null) throw new BizException(ErrorCode.NOT_FOUND, "权限点不存在: " + id);
        return p;
    }
}
