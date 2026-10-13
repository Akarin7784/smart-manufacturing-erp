package com.akarin.erp.auth.security;

/** 校验 token 对应用户当前是否仍可用（未删除、启用、令牌版本一致） */
public interface UserTokenState {
    boolean isValid(String username, Integer tokenVersion);
}
