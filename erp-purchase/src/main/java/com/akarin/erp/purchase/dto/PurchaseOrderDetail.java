package com.akarin.erp.purchase.dto;

import com.akarin.erp.purchase.entity.PurchaseOrder;
import com.akarin.erp.purchase.entity.PurchaseOrderItem;
import java.util.List;

public record PurchaseOrderDetail(PurchaseOrder order, List<PurchaseOrderItem> items) {}
