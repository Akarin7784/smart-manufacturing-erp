package com.akarin.erp.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.LocalDateTime;

@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor i = new MybatisPlusInterceptor();
        i.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        i.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return i;
    }

    @Bean
    public MetaObjectHandler auditFieldHandler() {
        return new MetaObjectHandler() {
            @Override public void insertFill(MetaObject m) {
                strictInsertFill(m, "createdAt", LocalDateTime.class, LocalDateTime.now());
                strictInsertFill(m, "updatedAt", LocalDateTime.class, LocalDateTime.now());
                var auth = SecurityContextHolder.getContext().getAuthentication();
                strictInsertFill(m, "createdBy", String.class, auth == null ? "system" : auth.getName());
                strictInsertFill(m, "deleted", Integer.class, 0);
            }
            @Override public void updateFill(MetaObject m) {
                strictUpdateFill(m, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
