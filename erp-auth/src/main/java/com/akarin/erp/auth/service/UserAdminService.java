package com.akarin.erp.auth.service;

import com.akarin.erp.auth.dto.UserReqs;
import com.akarin.erp.auth.dto.UserView;
import com.akarin.erp.auth.entity.SysRole;
import com.akarin.erp.auth.entity.SysUser;
import com.akarin.erp.auth.entity.SysUserRole;
import com.akarin.erp.auth.mapper.SysRoleMapper;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.auth.mapper.SysUserRoleMapper;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.util.SecurityUtils;
import com.akarin.erp.common.util.Versions;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserAdminService {
    public static final String ADMIN_ROLE = "ADMIN";
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder encoder;

    public Page<UserView> page(long page, long size, String keyword) {
        Page<SysUser> p = userMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<SysUser>()
                .and(StringUtils.hasText(keyword), w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword))
                .orderByAsc(SysUser::getId));
        Page<UserView> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        out.setRecords(p.getRecords().stream().map(this::view).toList());
        return out;
    }

    public UserView get(Long id) { return view(require(id)); }

    @Transactional
    public UserView create(UserReqs.Create req) {
        if (userMapper.countByUsernameIncludingDeleted(req.username()) > 0)
            throw new BizException(ErrorCode.CODE_DUPLICATED, "编码已存在（含已删除）: 用户名 " + req.username());
        SysUser u = new SysUser();
        u.setUsername(req.username());
        u.setPassword(encoder.encode(req.password()));
        u.setRealName(req.realName());
        u.setStatus(SysUser.ENABLED);
        u.setTokenVersion(0);
        u.setVersion(0);
        try {
            userMapper.insert(u);
        } catch (org.springframework.dao.DuplicateKeyException race) {   // 并发同编码创建
            throw new BizException(ErrorCode.CODE_DUPLICATED, "编码已存在: 用户名 " + req.username());
        }
        if (req.roleIds() != null && !req.roleIds().isEmpty()) replaceRoles(u, req.roleIds());
        return view(u);
    }

    @Transactional
    public UserView update(Long id, UserReqs.Update req) {
        SysUser u = require(id);
        com.akarin.erp.common.util.Versions.requirePresent(req.version());
        // 只更新可编辑字段（real_name），WHERE id + 客户端 version；不整行写回，避免覆盖并发提交的 status / password / token_version
        Versions.requireOneRow(userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id).eq(SysUser::getVersion, req.version())
                .set(SysUser::getRealName, req.realName())
                .set(SysUser::getUpdatedAt, java.time.LocalDateTime.now())
                .setSql("version = version + 1")));
        return view(require(id));
    }

    @Transactional
    public void resetPassword(Long id, String password) {
        require(id);
        // 定向更新，不校验 version，但会递增 version：并发的“改姓名”拿着旧 version 提交会得到 40900，
        // 而不会把旧的 status / password / token_version 写回去。前端拿旧 version 提交得到 40900 是预期行为（重新查详情即可）。
        Versions.requireOneRow(userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getPassword, encoder.encode(password))
                .set(SysUser::getUpdatedAt, java.time.LocalDateTime.now())
                .setSql("token_version = token_version + 1, version = version + 1")));
    }

    @Transactional
    public UserView setEnabled(Long id, boolean enabled) {
        SysUser u = require(id);
        if (!enabled) {
            if (isSelf(u)) throw new BizException("不能停用当前登录用户");
            guardLastAdmin(u, "停用");
        }
        // 定向更新，不校验 version，但会递增 version：并发的“改姓名”拿着旧 version 提交会得到 40900，
        // 而不会把旧的 status / password / token_version 写回去。前端拿旧 version 提交得到 40900 是预期行为（重新查详情即可）。
        Versions.requireOneRow(userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id)
                .set(SysUser::getStatus, enabled ? SysUser.ENABLED : SysUser.DISABLED)
                .set(SysUser::getUpdatedAt, java.time.LocalDateTime.now())
                .setSql("token_version = token_version + 1, version = version + 1")));
        return view(require(id));
    }

    @Transactional
    public UserView assignRoles(Long id, List<Long> roleIds) {
        SysUser u = require(id);
        replaceRoles(u, roleIds);
        Versions.requireOneRow(userMapper.bumpTokenVersion(id));   // 定向 SQL：不校验 version，但 token_version 和 version 都 +1
        return view(require(id));
    }

    @Transactional
    public void delete(Long id) {
        SysUser u = require(id);
        if (isSelf(u)) throw new BizException("不能删除当前登录用户");
        guardLastAdmin(u, "删除");
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        Versions.requireOneRow(userMapper.bumpTokenVersion(id));
        Versions.requireOneRow(userMapper.deleteById(id));   // 按 id 软删，不带 version 条件
    }

    private void replaceRoles(SysUser u, List<Long> roleIds) {
        var ids = new LinkedHashSet<>(roleIds);
        List<SysRole> roles = ids.isEmpty() ? List.of() : roleMapper.selectBatchIds(ids);
        if (roles.size() != ids.size()) throw new BizException(ErrorCode.BAD_REQUEST, "角色不存在");
        boolean keepsAdmin = roles.stream().anyMatch(r -> ADMIN_ROLE.equals(r.getCode()));
        boolean wasAdmin = userMapper.selectRoleCodes(u.getId()).contains(ADMIN_ROLE);
        if (keepsAdmin != wasAdmin && !SecurityUtils.currentAuthorities().contains("ROLE_" + ADMIN_ROLE))
            throw new BizException(ErrorCode.FORBIDDEN, "只有管理员能分配或移除 ADMIN 角色");
        if (!keepsAdmin) guardLastAdmin(u, "移除管理员角色");
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, u.getId()));
        ids.forEach(rid -> userRoleMapper.insert(new SysUserRole(u.getId(), rid)));
    }

    /** 该用户是启用的管理员且是最后一个时，禁止停用/删除/去掉 ADMIN */
    private void guardLastAdmin(SysUser u, String action) {
        boolean enabledAdmin = Objects.equals(u.getStatus(), SysUser.ENABLED)
                && userMapper.selectRoleCodes(u.getId()).contains(ADMIN_ROLE);
        if (enabledAdmin && userMapper.countEnabledAdmins() <= 1)
            throw new BizException("不能" + action + "最后一个管理员");
    }

    private boolean isSelf(SysUser u) { return u.getUsername().equals(SecurityUtils.currentUsername()); }

    private SysUser require(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new BizException(ErrorCode.NOT_FOUND, "用户不存在: " + id);
        return u;
    }

    private UserView view(SysUser u) {
        return new UserView(u.getId(), u.getUsername(), u.getRealName(),
                Objects.equals(u.getStatus(), SysUser.ENABLED), userMapper.selectRoleCodes(u.getId()), u.getVersion());
    }
}
