package com.akarin.erp.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentCreateReq(@NotBlank String paymentType, @NotNull Long partnerId,
                               @NotNull @Positive BigDecimal amount, LocalDate paidDate, String remark) {}
