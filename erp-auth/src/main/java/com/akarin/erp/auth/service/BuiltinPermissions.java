package com.akarin.erp.auth.service;

import java.util.Set;

/** data.sql 内置的权限点，代码里按编码引用，不允许删除 */
public final class BuiltinPermissions {
    private BuiltinPermissions() {}
    public static final Set<String> CODES = Set.of(
            "mdm:write", "purchase:write", "sales:write", "inventory:write", "workflow:approve",
            "finance:write", "finance:read", "sys:read", "sys:write", "workflow:admin");
}
