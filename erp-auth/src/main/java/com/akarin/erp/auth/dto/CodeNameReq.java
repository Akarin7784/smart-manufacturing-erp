package com.akarin.erp.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 角色 / 权限点的创建与更新请求；更新时 code 不可修改 */
public record CodeNameReq(@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_:.-]+$", message = "只能包含字母、数字、_ : . -") String code,
                          @NotBlank @Size(max = 64) String name) {}
