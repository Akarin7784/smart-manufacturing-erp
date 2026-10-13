package com.akarin.erp.auth.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册"先判权限再绑定参数"的拦截器 */
@Configuration
public class WebSecurityMvcConfig implements WebMvcConfigurer {
    private final MethodSecurityExpressionHandler handler;

    public WebSecurityMvcConfig(MethodSecurityExpressionHandler handler) { this.handler = handler; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PreAuthorizeBeforeBindingInterceptor(handler)).addPathPatterns("/api/**");
    }
}
