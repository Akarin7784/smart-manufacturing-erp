package com.akarin.erp.common.event;

import java.math.BigDecimal;

/**
 * 业务单据完成出入库后发布（同步、同一事务），财务据此生成应收/应付。
 * @param bizType   PURCHASE_RECEIPT / SALES_SHIPMENT
 * @param bizNo     来源单号（订单号），用于幂等
 * @param partnerId 供应商或客户 ID
 * @param amount    单据金额（Σ 数量×单价）
 */
public record StockBizCompletedEvent(String bizType, String bizNo, Long partnerId, BigDecimal amount) {
    public static final String PURCHASE_RECEIPT = "PURCHASE_RECEIPT";
    public static final String SALES_SHIPMENT = "SALES_SHIPMENT";
}
