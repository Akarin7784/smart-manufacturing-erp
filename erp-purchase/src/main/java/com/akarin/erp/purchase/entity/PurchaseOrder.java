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
    /** DRAFT -> PENDING -> APPROVED -> RECEIVED；驳回回到 DRAFT */
    private String status;
    private BigDecimal totalAmount;
    private String remark;
    /** 幂等键：与 Idempotency-Key 同事务写入；UNIQUE(idem_user, idem_key)，未带请求头时为 null */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String idemUser;
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String idemKey;
    /** 请求参数摘要：幂等记录清理后同 key 换参数复用时据此返回 40902，而不是重放旧单据 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String idemHash;
    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;
}
