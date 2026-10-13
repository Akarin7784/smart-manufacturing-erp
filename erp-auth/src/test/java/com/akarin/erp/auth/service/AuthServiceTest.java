package com.akarin.erp.auth.service;

import com.akarin.erp.auth.entity.SysUser;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.auth.security.JwtProperties;
import com.akarin.erp.auth.security.JwtTokenProvider;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 登录、RBAC 权限汇总、token 有效性（停用/版本变化即失效） */
class AuthServiceTest {
    @BeforeAll
    static void initMybatisLambdaCache() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : new Class<?>[]{SysUser.class}) TableInfoHelper.initTableInfo(assistant, c);
    }

    SysUserMapper mapper = mock(SysUserMapper.class);
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    JwtTokenProvider jwt;
    AuthService svc;

    AuthServiceTest() {
        JwtProperties p = new JwtProperties();
        p.setSecret("unit-test-secret-unit-test-secret-0123456789");
        jwt = new JwtTokenProvider(p);
        svc = new AuthService(mapper, encoder, jwt);
    }

    SysUser user(int status, Integer tv) {
        SysUser u = new SysUser();
        u.setId(5L); u.setUsername("bob"); u.setPassword(encoder.encode("secret1")); u.setStatus(status); u.setTokenVersion(tv);
        return u;
    }

    @Test void loginIssuesTokenWithPermissionsAndRoles() {
        when(mapper.selectOne(any())).thenReturn(user(1, 3));
        when(mapper.selectPermissionCodes(5L)).thenReturn(List.of("purchase:write"));
        when(mapper.selectRoleCodes(5L)).thenReturn(List.of("BUYER"));
        var claims = jwt.parse(svc.login("bob", "secret1"));
        assertThat(claims.get("auth", List.class)).containsExactlyInAnyOrder("purchase:write", "ROLE_BUYER");
        assertThat(claims.get("tv", Integer.class)).isEqualTo(3);
    }

    @Test void wrongPasswordOrUnknownUserIs401() {
        when(mapper.selectOne(any())).thenReturn(user(1, 0));
        assertThatThrownBy(() -> svc.login("bob", "bad")).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> svc.login("nobody", "x")).isInstanceOf(BizException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test void disabledUserCannotLogin() {
        when(mapper.selectOne(any())).thenReturn(user(0, 0));
        assertThatThrownBy(() -> svc.login("bob", "secret1")).extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test void tokenValidity() {
        when(mapper.selectOne(any())).thenReturn(user(1, 2));
        assertThat(svc.isValid("bob", 2)).isTrue();
        assertThat(svc.isValid("bob", 1)).as("版本变化").isFalse();
        assertThat(svc.isValid("bob", null)).isFalse();
        assertThat(svc.isValid(null, 2)).isFalse();
        when(mapper.selectOne(any())).thenReturn(user(0, 2));
        assertThat(svc.isValid("bob", 2)).as("已停用").isFalse();
        when(mapper.selectOne(any())).thenReturn(user(1, null));
        assertThat(svc.isValid("bob", 0)).as("旧数据 token_version 为空按 0").isTrue();
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(svc.isValid("bob", 0)).as("已删除").isFalse();
    }

    @Test void approverDirectoryDelegates() {
        when(mapper.selectUsernamesWithRole("R")).thenReturn(List.of("a", "b"));
        assertThat(new AuthApproverDirectory(mapper).usernamesWithRole("R")).containsExactly("a", "b");
    }
}
