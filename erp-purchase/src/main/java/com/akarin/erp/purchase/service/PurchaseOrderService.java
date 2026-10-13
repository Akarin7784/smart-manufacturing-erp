package com.akarin.erp.purchase.service;

import com.akarin.erp.common.event.ApprovalFinishedEvent;
import com.akarin.erp.common.event.StockBizCompletedEvent;
import com.akarin.erp.common.util.Money;
import org.springframework.context.ApplicationEventPublisher;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.util.NoGenerator;
import com.akarin.erp.inventory.service.InventoryService;
import com.akarin.erp.mdm.service.MaterialService;
import com.akarin.erp.mdm.service.SupplierService;
import com.akarin.erp.mdm.service.WarehouseService;
import com.akarin.erp.purchase.dto.PurchaseOrderCreateReq;
import com.akarin.erp.purchase.dto.PurchaseOrderDetail;
import com.akarin.erp.purchase.entity.PurchaseOrder;
import com.akarin.erp.purchase.entity.PurchaseOrderItem;
import com.akarin.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.akarin.erp.purchase.mapper.PurchaseOrderMapper;
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
public class PurchaseOrderService extends ServiceImpl<PurchaseOrderMapper, PurchaseOrder> {
    public static final String BIZ_TYPE = "PURCHASE_ORDER";
    private final PurchaseOrderItemMapper itemMapper;
    private final MaterialService materialService;
    private final SupplierService supplierService;
    private final WarehouseService warehouseService;
    private final ApprovalService approvalService;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher publisher;

    @Transactional
    public PurchaseOrderDetail create(PurchaseOrderCreateReq req) {
        supplierService.require(req.supplierId());
        warehouseService.require(req.warehouseId());
        PurchaseOrder o = new PurchaseOrder();
        o.setOrderNo(NoGenerator.next("PO"));
        o.setSupplierId(req.supplierId());
        o.setWarehouseId(req.warehouseId());
        o.setRemark(req.remark());
        o.setStatus("DRAFT");
        o.setTotalAmount(BigDecimal.ZERO);
        save(o);
        BigDecimal total = BigDecimal.ZERO;
        for (var l : req.items()) {
            materialService.require(l.materialId());
            PurchaseOrderItem it = new PurchaseOrderItem();
            it.setOrderId(o.getId()); it.setMaterialId(l.materialId());
            it.setQty(l.qty()); it.setPrice(l.price()); it.setAmount(Money.lineAmount(l.qty(), l.price()));
            itemMapper.insert(it);
            total = Money.of(total.add(it.getAmount()));
        }
        o.setTotalAmount(total);
        updateById(o);
        return detail(o.getId());
    }

    public PurchaseOrderDetail detail(Long id) {
        PurchaseOrder o = require(id);
        List<PurchaseOrderItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getOrderId, id));
        return new PurchaseOrderDetail(o, items);
    }

    /** 提交审批 */
    @Transactional
    public Long submit(Long id) {
        PurchaseOrder o = require(id);
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus()))
            throw new BizException("当前状态不可提交: " + o.getStatus());
        o.setStatus("PENDING");
        updateById(o);
        return approvalService.start(BIZ_TYPE, o.getId(), o.getOrderNo()).getId();
    }

    /** 审批结果回调 */
    @EventListener
    public void onApproval(ApprovalFinishedEvent e) {
        if (!BIZ_TYPE.equals(e.bizType())) return;
        PurchaseOrder o = require(e.bizId());
        o.setStatus(e.approved() ? "APPROVED" : "REJECTED");
        updateById(o);
    }

    /** 采购收货：按订单明细整单入库（骨架；后续支持分批收货、到货单） */
    @Transactional
    public PurchaseOrderDetail receive(Long id) {
        PurchaseOrderDetail d = detail(id);
        if (!"APPROVED".equals(d.order().getStatus()))
            throw new BizException("仅已审批单据可执行该操作，当前: " + d.order().getStatus());
        // 带状态条件的更新：并发重复收货时只有一个请求能把 APPROVED 改成 RECEIVED，其余返回 409/40900
        boolean moved = lambdaUpdate().set(PurchaseOrder::getStatus, "RECEIVED")
                .eq(PurchaseOrder::getId, id).eq(PurchaseOrder::getStatus, "APPROVED").update();
        if (!moved) throw new BizException(ErrorCode.CONCURRENT_MODIFICATION, "单据状态已变化，请刷新后重试");
        d.order().setStatus("RECEIVED");
        for (var it : d.items()) {
            inventoryService.stockIn(it.getMaterialId(), d.order().getWarehouseId(), it.getQty(),
                    "PURCHASE_RECEIPT", d.order().getOrderNo(), null);
        }
        BigDecimal amount = Money.sum(d.items().stream().map(i -> Money.lineAmount(i.getQty(), i.getPrice())).toList());
        publisher.publishEvent(new StockBizCompletedEvent(StockBizCompletedEvent.PURCHASE_RECEIPT,
                d.order().getOrderNo(), d.order().getSupplierId(), amount));
        return d;
    }

    private PurchaseOrder require(Long id) {
        PurchaseOrder o = getById(id);
        if (o == null) throw new BizException("单据不存在: " + id);
        return o;
    }
}
