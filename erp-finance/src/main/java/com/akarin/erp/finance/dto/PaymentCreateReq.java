package com.akarin.erp.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentCreateReq(@NotBlank String paymentType, @NotNull Long partnerId,
                               @NotNull(message = "收付款金额不能为空") @DecimalMin(value = "0.01", message = "收付款金额必须 ≥ 0.01") @Digits(integer = 16, fraction = 2, message = "收付款金额最多 2 位小数、16 位整数") BigDecimal amount, LocalDate paidDate, String remark) {}
