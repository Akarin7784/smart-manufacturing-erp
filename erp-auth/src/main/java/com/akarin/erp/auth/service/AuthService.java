package com.akarin.erp.auth.service;

import com.akarin.erp.auth.entity.SysUser;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.auth.security.JwtTokenProvider;
import com.akarin.erp.auth.security.UserTokenState;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AuthService implements UserTokenState {
    private final SysUserMapper userMapper;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider tokens;

    public String login(String username, String password) {
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (u == null || !encoder.matches(password, u.getPassword())) throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        if (!Objects.equals(u.getStatus(), SysUser.ENABLED)) throw new BizException(ErrorCode.FORBIDDEN, "账号已停用");
        return tokens.create(u.getUsername(), authoritiesOf(u.getId()), tv(u));
    }

    public List<String> authoritiesOf(Long userId) {
        List<String> auth = new ArrayList<>(userMapper.selectPermissionCodes(userId));
        userMapper.selectRoleCodes(userId).forEach(r -> auth.add("ROLE_" + r));
        return auth;
    }

    /** 每次请求校验：用户存在、启用、令牌版本未变化（停用/改密/调整角色后旧 token 立即失效） */
    @Override
    public boolean isValid(String username, Integer tokenVersion) {
        if (username == null || tokenVersion == null) return false;
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        return u != null && Objects.equals(u.getStatus(), SysUser.ENABLED) && tv(u) == tokenVersion;
    }

    private static int tv(SysUser u) { return u.getTokenVersion() == null ? 0 : u.getTokenVersion(); }
}
