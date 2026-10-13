package com.akarin.erp.sales.service;

import com.akarin.erp.common.event.ApprovalFinishedEvent;
import com.akarin.erp.common.event.StockBizCompletedEvent;
import com.akarin.erp.common.util.Money;
import org.springframework.context.ApplicationEventPublisher;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.util.NoGenerator;
import com.akarin.erp.inventory.service.InventoryService;
import com.akarin.erp.mdm.service.MaterialService;
import com.akarin.erp.mdm.service.CustomerService;
import com.akarin.erp.mdm.service.WarehouseService;
import com.akarin.erp.sales.dto.SalesOrderCreateReq;
import com.akarin.erp.sales.dto.SalesOrderDetail;
import com.akarin.erp.sales.entity.SalesOrder;
import com.akarin.erp.sales.entity.SalesOrderItem;
import com.akarin.erp.sales.mapper.SalesOrderItemMapper;
import com.akarin.erp.sales.mapper.SalesOrderMapper;
import com.akarin.erp.workflow.service.ApprovalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SalesOrderService extends ServiceImpl<SalesOrderMapper, SalesOrder> {
    public static final String BIZ_TYPE = "SALES_ORDER";
    private final SalesOrderItemMapper itemMapper;
    private final MaterialService materialService;
    private final CustomerService customerService;
    private final WarehouseService warehouseService;
    private final ApprovalService approvalService;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public SalesOrderDetail create(SalesOrderCreateReq req) {
        customerService.requireRef(req.customerId());
        warehouseService.requireRef(req.warehouseId());
        SalesOrder o = new SalesOrder();
        o.setOrderNo(NoGenerator.next("SO"));
        o.setCustomerId(req.customerId());
        o.setWarehouseId(req.warehouseId());
        o.setRemark(req.remark());
        o.setStatus("DRAFT");
        o.setTotalAmount(BigDecimal.ZERO);
        save(o);
        BigDecimal total = BigDecimal.ZERO;
        for (var l : req.items()) {
            materialService.requireRef(l.materialId());
            SalesOrderItem it = new SalesOrderItem();
            it.setOrderId(o.getId()); it.setMaterialId(l.materialId());
            it.setQty(l.qty()); it.setPrice(l.price()); it.setAmount(Money.lineAmount(l.qty(), l.price()));
            itemMapper.insert(it);
            total = Money.of(total.add(it.getAmount()));
        }
        o.setTotalAmount(total);
        updateById(o);
        return detail(o.getId());
    }

    public SalesOrderDetail detail(Long id) {
        SalesOrder o = require(id);
        List<SalesOrderItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>().eq(SalesOrderItem::getOrderId, id));
        return new SalesOrderDetail(o, items);
    }

    /** 提交审批 */
    @Transactional
    public Long submit(Long id) {
        SalesOrder o = require(id);
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus()))
            throw new BizException("当前状态不可提交: " + o.getStatus());
        o.setStatus("PENDING");
        updateById(o);
        return approvalService.start(BIZ_TYPE, o.getId(), o.getOrderNo(), o.getTotalAmount()).getId();
    }

    /** 审批结果回调 */
    @EventListener
    public void onApproval(ApprovalFinishedEvent e) {
        if (!BIZ_TYPE.equals(e.bizType())) return;
        SalesOrder o = require(e.bizId());
        // 驳回回到草稿，可修改后重新提交
        o.setStatus(e.approved() ? "APPROVED" : "DRAFT");
        updateById(o);
    }

    /** 销售发货：按订单明细整单出库（骨架；后续支持发货单、部分发货） */
    @Transactional
    public SalesOrderDetail ship(Long id) {
        SalesOrderDetail d = detail(id);
        if (!"APPROVED".equals(d.order().getStatus()))
            throw new BizException("仅已审批单据可执行该操作，当前: " + d.order().getStatus());
        // 带状态条件的更新：并发重复发货时只有一个请求能把 APPROVED 改成 SHIPPED，其余返回 409/40900
        boolean moved = lambdaUpdate().set(SalesOrder::getStatus, "SHIPPED")
                .eq(SalesOrder::getId, id).eq(SalesOrder::getStatus, "APPROVED").update();
        if (!moved) throw new BizException(ErrorCode.CONCURRENT_MODIFICATION, "单据状态已变化，请刷新后重试");
        d.order().setStatus("SHIPPED");
        for (var it : d.items()) {
            inventoryService.stockOut(it.getMaterialId(), d.order().getWarehouseId(), it.getQty(),
                    "SALES_SHIPMENT", d.order().getOrderNo(), null);
        }
        BigDecimal amount = Money.sum(d.items().stream().map(i -> Money.lineAmount(i.getQty(), i.getPrice())).toList());
        publisher.publishEvent(new StockBizCompletedEvent(StockBizCompletedEvent.SALES_SHIPMENT,
                d.order().getOrderNo(), d.order().getCustomerId(), amount));
        return d;
    }

    private SalesOrder require(Long id) {
        SalesOrder o = getById(id);
        if (o == null) throw new BizException("单据不存在: " + id);
        return o;
    }
}
