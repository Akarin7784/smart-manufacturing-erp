package com.akarin.erp.common.validation;

import jakarta.validation.Payload;

/**
 * 校验约束的显式标记：message 已是完整的提示（自带字段名），统一异常处理原样返回，不再在前面拼字段名。
 * 用法：{@code @DecimalMin(value = "0.01", message = "核销金额必须 ≥ 0.01", payload = FullMessage.class)}
 */
public interface FullMessage extends Payload {}
