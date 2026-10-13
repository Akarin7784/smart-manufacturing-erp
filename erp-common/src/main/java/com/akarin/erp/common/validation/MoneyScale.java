package com.akarin.erp.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 金额 / 数量的位数约束：先去掉末尾的 0（stripTrailingZeros）再检查整数位和小数位，
 * 因此 1.230、1.2300 视为 2 位小数。规则与 {@link com.akarin.erp.common.util.Money#scaleError} 完全一致。
 * null 不在这里判断（交给 @NotNull）。提示为中文且带字段名，校验失败时原样返回。
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
    /** 提示已自带字段名：统一异常处理对本约束总是原样返回（见 GlobalExceptionHandler.isFullMessage） */
    Class<? extends Payload>[] payload() default {};
}
