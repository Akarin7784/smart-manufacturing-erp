package com.akarin.erp.finance.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 应收/应付单（骨架） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_arap_bill")
public class ArapBill extends BaseEntity {
    private String billNo;
    /** AR 应收 / AP 应付 */
    private String billType;
    /** 往来单位：客户或供应商 ID */
    private Long partnerId;
    private String sourceBizType;
    private String sourceBizNo;
    private BigDecimal amount;
    private BigDecimal settledAmount;
    /** OPEN / PARTIAL / SETTLED */
    private String status;
    private LocalDate dueDate;
}
