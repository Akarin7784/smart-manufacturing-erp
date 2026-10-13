package com.akarin.erp.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/** requestNo 由调用方生成（如 UUID），同一请求号重复提交只生效一次 */
public record SettleReq(@NotBlank String requestNo, @NotNull Long paymentId, @NotNull Long billId,
                        @NotNull @DecimalMin("0.01") @Digits(integer = 16, fraction = 2) BigDecimal amount) {}
