package com.akarin.erp.auth.service;

import com.akarin.erp.auth.entity.SysUser;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.auth.security.JwtTokenProvider;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final SysUserMapper userMapper;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider tokens;

    public String login(String username, String password) {
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (u == null || !encoder.matches(password, u.getPassword())) throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        if (u.getStatus() != null && u.getStatus() == 0) throw new BizException(ErrorCode.FORBIDDEN, "账号已停用");
        List<String> auth = new ArrayList<>(userMapper.selectPermissionCodes(u.getId()));
        userMapper.selectRoleCodes(u.getId()).forEach(r -> auth.add("ROLE_" + r));
        return tokens.create(u.getUsername(), auth);
    }
}
