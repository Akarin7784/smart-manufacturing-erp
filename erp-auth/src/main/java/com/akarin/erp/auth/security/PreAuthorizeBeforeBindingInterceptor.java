package com.akarin.erp.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在参数绑定和 @Valid 校验之前先按 @PreAuthorize 判权限：没有写权限的用户调写接口直接 403，
 * 不会因为请求体缺字段先拿到 400（否则等于向无权用户暴露接口的校验规则）。
 * 方法上的 @PreAuthorize 照常生效，这里只是提前做一次同样的判断；只支持不引用方法参数的表达式（本项目都是 hasAuthority）。
 */
public class PreAuthorizeBeforeBindingInterceptor implements HandlerInterceptor {
    private final MethodSecurityExpressionHandler handler;
    private final Map<String, Expression> cache = new ConcurrentHashMap<>();

    public PreAuthorizeBeforeBindingInterceptor(MethodSecurityExpressionHandler handler) { this.handler = handler; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object h) {
        if (!(h instanceof HandlerMethod hm)) return true;
        PreAuthorize pa = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), PreAuthorize.class);
        if (pa == null) pa = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), PreAuthorize.class);
        if (pa == null) return true;
        ExpressionParser parser = handler.getExpressionParser();
        Expression exp = cache.computeIfAbsent(pa.value(), parser::parseExpression);
        var ctx = handler.createEvaluationContext(SecurityContextHolder.getContext()::getAuthentication,
                new SimpleMethodInvocation(hm.getBean(), hm.getMethod()));
        if (!Boolean.TRUE.equals(exp.getValue(ctx, Boolean.class))) throw new AccessDeniedException("无权限");
        return true;
    }
}
