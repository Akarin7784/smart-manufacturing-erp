package com.akarin.erp.sales.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sal_order")
public class SalesOrder extends BaseEntity {
    private String orderNo;
    private Long customerId;
    private Long warehouseId;
    /** DRAFT -> PENDING -> APPROVED -> SHIPPED；驳回回到 DRAFT */
    private String status;
    private BigDecimal totalAmount;
    private String remark;
    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;
}
