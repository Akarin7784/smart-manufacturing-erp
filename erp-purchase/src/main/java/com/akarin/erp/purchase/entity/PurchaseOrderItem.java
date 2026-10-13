package com.akarin.erp.purchase.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pur_order_item")
public class PurchaseOrderItem extends BaseEntity {
    private Long orderId;
    private Long materialId;
    private BigDecimal qty;
    private BigDecimal price;
    private BigDecimal amount;
}
