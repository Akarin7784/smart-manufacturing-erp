package com.akarin.erp.common.validation;

import jakarta.validation.groups.Default;

/** 校验分组：只在创建时生效的约束（如编码必填）。继承 Default，所以创建时普通约束也照常校验 */
public interface OnCreate extends Default {}
