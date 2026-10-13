package com.akarin.erp.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentCreateReq(@NotBlank String paymentType, @NotNull Long partnerId,
                               @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount, LocalDate paidDate, String remark) {}
