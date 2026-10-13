package com.akarin.erp.auth.service;

import com.akarin.erp.auth.dto.CodeNameReq;
import com.akarin.erp.auth.dto.UserReqs;
import com.akarin.erp.auth.entity.SysPermission;
import com.akarin.erp.auth.entity.SysRole;
import com.akarin.erp.auth.entity.SysUser;
import com.akarin.erp.auth.init.AdminInitializer;
import com.akarin.erp.auth.mapper.*;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** 查重通过后插入时撞唯一键（并发同编码创建）→ 400/40001；以及 admin 初始密码缺失时启动失败 */
class AdminServiceDuplicateKeyTest {
    @BeforeAll static void init() {
        var a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : new Class<?>[]{SysUser.class, SysRole.class, SysPermission.class}) TableInfoHelper.initTableInfo(a, c);
    }

    SysUserMapper userMapper = mock(SysUserMapper.class);
    SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    SysPermissionMapper permissionMapper = mock(SysPermissionMapper.class);

    @Test void userInsertDuplicateKeyIs40001() {
        when(userMapper.countByUsernameIncludingDeleted("bob")).thenReturn(0L);
        when(userMapper.insert(any(SysUser.class))).thenThrow(new DuplicateKeyException("uk_username"));
        var svc = new UserAdminService(userMapper, roleMapper, mock(SysUserRoleMapper.class), NoOpPasswordEncoder.getInstance());
        assertThatThrownBy(() -> svc.create(new UserReqs.Create("bob", "pw123456", null, null)))
                .isInstanceOf(BizException.class).hasMessageStartingWith("编码已存在")
                .extracting("errorCode").isEqualTo(ErrorCode.CODE_DUPLICATED);
    }

    @Test void roleInsertDuplicateKeyIs40001() {
        when(roleMapper.countByCodeIncludingDeleted("OPS")).thenReturn(0L);
        when(roleMapper.insert(any(SysRole.class))).thenThrow(new DuplicateKeyException("uk_code"));
        var svc = new RoleAdminService(roleMapper, permissionMapper, mock(SysRolePermissionMapper.class), mock(SysUserRoleMapper.class), userMapper);
        assertThatThrownBy(() -> svc.create(new CodeNameReq("OPS", "运维", null)))
                .isInstanceOf(BizException.class).hasMessageStartingWith("编码已存在")
                .extracting("errorCode").isEqualTo(ErrorCode.CODE_DUPLICATED);
    }

    @Test void adminInitializerRequiresPasswordWhenNotSet() {
        SysUser admin = new SysUser(); admin.setUsername("admin"); admin.setPassword("");
        when(userMapper.selectOne(any())).thenReturn(admin);
        var init = new AdminInitializer(userMapper, NoOpPasswordEncoder.getInstance());
        ReflectionTestUtils.setField(init, "adminPassword", "");
        assertThatThrownBy(() -> init.run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("ERP_ADMIN_PASSWORD");
        ReflectionTestUtils.setField(init, "adminPassword", "s3cret!");
        when(userMapper.update(isNull(), any())).thenReturn(1);
        init.run(null);
        verify(userMapper).update(isNull(), any());
        verify(userMapper, never()).updateById(any(SysUser.class));
        when(userMapper.update(isNull(), any())).thenReturn(0);
        assertThatThrownBy(() -> init.run(null)).hasMessage("初始化 admin 密码失败");
    }
}
