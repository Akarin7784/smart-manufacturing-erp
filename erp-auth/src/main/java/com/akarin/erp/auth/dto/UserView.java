package com.akarin.erp.auth.dto;

import java.util.List;

/** 对外返回的用户视图（不含密码与令牌版本） */
public record UserView(Long id, String username, String realName, boolean enabled, List<String> roleCodes, Integer version) {}
