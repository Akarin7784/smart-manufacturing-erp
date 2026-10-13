package com.akarin.erp.auth.security;

import org.aopalliance.intercept.MethodInvocation;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionOperations;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;

import java.util.function.Supplier;

/**
 * 防止管理员被锁在系统外：持有 ROLE_ADMIN 时，@PreAuthorize 里的 hasAuthority / hasAnyAuthority 一律放行，
 * 即使 ADMIN 角色的权限点被清空。
 */
public class AdminBypassExpressionHandler extends DefaultMethodSecurityExpressionHandler {
    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    @Override
    public EvaluationContext createEvaluationContext(Supplier<Authentication> authentication, MethodInvocation mi) {
        StandardEvaluationContext ctx = (StandardEvaluationContext) super.createEvaluationContext(authentication, mi);
        AdminAwareRoot root = new AdminAwareRoot(authentication);
        root.setTrustResolver(new AuthenticationTrustResolverImpl());
        root.setPermissionEvaluator(getPermissionEvaluator());
        root.setDefaultRolePrefix(getDefaultRolePrefix());
        root.setThis(mi.getThis());
        ctx.setRootObject(root);
        return ctx;
    }

    static boolean isAdmin(Authentication a) {
        return a != null && a.getAuthorities().stream().anyMatch(g -> ADMIN_AUTHORITY.equals(g.getAuthority()));
    }

    /** SecurityExpressionRoot 的 hasAuthority 是 final，所以用委托实现表达式根对象 */
    static class AdminAwareRoot implements MethodSecurityExpressionOperations {
        private final SecurityExpressionRoot delegate;
        private Object filterObject, returnObject, target;

        AdminAwareRoot(Supplier<Authentication> authentication) {
            delegate = new SecurityExpressionRoot(authentication) {};
        }
        void setTrustResolver(org.springframework.security.authentication.AuthenticationTrustResolver r) { delegate.setTrustResolver(r); }
        void setPermissionEvaluator(org.springframework.security.access.PermissionEvaluator p) { delegate.setPermissionEvaluator(p); }
        void setDefaultRolePrefix(String prefix) { delegate.setDefaultRolePrefix(prefix); }
        void setThis(Object t) { target = t; }

        private boolean admin() { return isAdmin(delegate.getAuthentication()); }

        @Override public Authentication getAuthentication() { return delegate.getAuthentication(); }
        @Override public boolean hasAuthority(String authority) { return admin() || delegate.hasAuthority(authority); }
        @Override public boolean hasAnyAuthority(String... authorities) { return admin() || delegate.hasAnyAuthority(authorities); }
        @Override public boolean hasRole(String role) { return delegate.hasRole(role); }
        @Override public boolean hasAnyRole(String... roles) { return delegate.hasAnyRole(roles); }
        @Override public boolean permitAll() { return true; }
        @Override public boolean denyAll() { return false; }
        @Override public boolean isAnonymous() { return delegate.isAnonymous(); }
        @Override public boolean isAuthenticated() { return delegate.isAuthenticated(); }
        @Override public boolean isRememberMe() { return delegate.isRememberMe(); }
        @Override public boolean isFullyAuthenticated() { return delegate.isFullyAuthenticated(); }
        @Override public boolean hasPermission(Object target, Object permission) { return delegate.hasPermission(target, permission); }
        @Override public boolean hasPermission(Object targetId, String targetType, Object permission) { return delegate.hasPermission(targetId, targetType, permission); }
        @Override public void setFilterObject(Object o) { filterObject = o; }
        @Override public Object getFilterObject() { return filterObject; }
        @Override public void setReturnObject(Object o) { returnObject = o; }
        @Override public Object getReturnObject() { return returnObject; }
        @Override public Object getThis() { return target; }
    }
}
