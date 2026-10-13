package com.akarin.erp.purchase.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pur_order")
public class PurchaseOrder extends BaseEntity {
    private String orderNo;
    private Long supplierId;
    private Long warehouseId;
    /** DRAFT -> PENDING -> APPROVED -> RECEIVED；驳回回到 REJECTED */
    private String status;
    private BigDecimal totalAmount;
    private String remark;
}
