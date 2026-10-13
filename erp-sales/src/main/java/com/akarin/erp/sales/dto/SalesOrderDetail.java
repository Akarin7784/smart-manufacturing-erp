package com.akarin.erp.sales.dto;

import com.akarin.erp.sales.entity.SalesOrder;
import com.akarin.erp.sales.entity.SalesOrderItem;
import java.util.List;

public record SalesOrderDetail(SalesOrder order, List<SalesOrderItem> items) {}
