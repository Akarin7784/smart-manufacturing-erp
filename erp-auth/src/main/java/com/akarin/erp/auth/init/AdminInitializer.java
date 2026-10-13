package com.akarin.erp.auth.init;

import com.akarin.erp.auth.entity.SysUser;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 首次启动时补齐 admin 的密码（schema 里的 admin 账号密码占位为空） */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {
    private final SysUserMapper userMapper;
    private final PasswordEncoder encoder;
    @Value("${erp.init.admin-password:}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        SysUser admin = userMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin"));
        if (admin != null && (admin.getPassword() == null || admin.getPassword().isEmpty())) {
            if (adminPassword == null || adminPassword.isBlank())
                throw new IllegalStateException("admin 尚未设置密码：请通过环境变量 ERP_ADMIN_PASSWORD 或配置 erp.init.admin-password 提供初始密码");
            // 定向更新密码，不走乐观锁（启动初始化，不应受 version 影响）
            int rows = userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser>()
                    .eq(SysUser::getId, admin.getId())
                    .set(SysUser::getPassword, encoder.encode(adminPassword)));
            if (rows != 1) throw new IllegalStateException("初始化 admin 密码失败");
            log.info("initialized admin password");
        }
    }
}
