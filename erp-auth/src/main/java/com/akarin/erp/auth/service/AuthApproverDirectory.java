package com.akarin.erp.auth.service;

import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.common.spi.ApproverDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AuthApproverDirectory implements ApproverDirectory {
    private final SysUserMapper userMapper;

    @Override
    public List<String> usernamesWithRole(String roleCode) { return userMapper.selectUsernamesWithRole(roleCode); }
}
