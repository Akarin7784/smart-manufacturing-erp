package com.akarin.erp.common.util;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.stream.Collectors;

public final class SecurityUtils {
    private SecurityUtils() {}

    public static String currentUsername() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "anonymous" : a.getName();
    }

    /** 当前用户的权限点与角色（角色以 ROLE_ 前缀） */
    public static Set<String> currentAuthorities() {
        var a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return Set.of();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }
}
