package com.akarin.erp.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 金额 / 数量的位数约束：先去掉末尾的 0（stripTrailingZeros）再检查整数位和小数位，
 * 因此 1.230、1.2300 视为 2 位小数。规则与 {@link com.akarin.erp.common.util.Money#scaleError} 完全一致。
 * null 不在这里判断（交给 @NotNull）。提示为中文且已带字段名（label）。
 * 使用时<b>不需要</b>手动加 payload = FullMessage.class：GlobalExceptionHandler 按注解类型识别 @MoneyScale，提示原样返回、不再拼字段名。
 */
@Documented
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MoneyScaleValidator.class)
public @interface MoneyScale {
    /** 字段名，用于提示，如"核销金额" */
    String label();
    int integer() default 16;
    int fraction() default 2;
    String message() default "";
    Class<?>[] groups() default {};
    /** 保持为空即可（Bean Validation 要求默认值为空数组）；原样返回由 handler 按注解类型判断 */
    Class<? extends Payload>[] payload() default {};
}
