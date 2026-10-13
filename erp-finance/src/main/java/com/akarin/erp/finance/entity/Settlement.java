package com.akarin.erp.finance.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

/** 核销记录；request_no 唯一保证幂等 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_settlement")
public class Settlement extends BaseEntity {
    private String requestNo;
    private Long paymentId;
    private Long billId;
    private BigDecimal amount;
}
