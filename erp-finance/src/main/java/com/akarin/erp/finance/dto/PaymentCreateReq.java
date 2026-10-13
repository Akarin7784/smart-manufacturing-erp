package com.akarin.erp.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentCreateReq(@NotBlank String paymentType, @NotNull Long partnerId,
                               @NotNull(message = "收付款金额不能为空", payload = com.akarin.erp.common.validation.FullMessage.class) @DecimalMin(value = "0.01", message = "收付款金额必须 ≥ 0.01", payload = com.akarin.erp.common.validation.FullMessage.class) @com.akarin.erp.common.validation.MoneyScale(label = "收付款金额") BigDecimal amount, LocalDate paidDate, String remark) {}
