package com.akarin.erp.inventory.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

/** 库存流水：每次出入库一条，用于对账与追溯 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_stock_txn")
public class StockTxn extends BaseEntity {
    private String txnNo;
    /** IN / OUT */
    private String direction;
    private Long materialId;
    private Long warehouseId;
    private BigDecimal qty;
    private BigDecimal balanceAfter;
    /** 来源单据类型：PURCHASE_RECEIPT / SALES_SHIPMENT / MANUAL */
    private String bizType;
    private String bizNo;
    private String remark;
}
