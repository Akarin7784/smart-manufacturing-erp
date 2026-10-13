package com.akarin.erp.common.validation;

import com.akarin.erp.common.util.Money;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.math.BigDecimal;

public class MoneyScaleValidator implements ConstraintValidator<MoneyScale, BigDecimal> {
    private MoneyScale a;

    @Override public void initialize(MoneyScale a) { this.a = a; }

    @Override
    public boolean isValid(BigDecimal v, ConstraintValidatorContext ctx) {
        String err = Money.scaleError(v, a.integer(), a.fraction(), a.label());
        if (err == null) return true;
        ctx.disableDefaultConstraintViolation();
        ctx.buildConstraintViolationWithTemplate(err.replace("{", "\\{").replace("$", "\\$")).addConstraintViolation();
        return false;
    }
}
