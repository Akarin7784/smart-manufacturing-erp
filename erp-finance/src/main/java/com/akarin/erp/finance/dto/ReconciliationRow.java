package com.akarin.erp.finance.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ReconciliationRow {
    private String billType;
    private Long partnerId;
    private Long billCount;
    private BigDecimal totalAmount;
    private BigDecimal settledAmount;
    private BigDecimal balance;
}
