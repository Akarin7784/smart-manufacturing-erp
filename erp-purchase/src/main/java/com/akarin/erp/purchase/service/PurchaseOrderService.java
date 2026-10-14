package com.akarin.erp.purchase.service;

import com.akarin.erp.common.event.ApprovalFinishedEvent;
import com.akarin.erp.common.event.StockBizCompletedEvent;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.util.Money;
import com.akarin.erp.common.util.NoGenerator;
import com.akarin.erp.common.util.Versions;
import com.akarin.erp.inventory.service.InventoryService;
import com.akarin.erp.mdm.service.MaterialService;
import com.akarin.erp.mdm.service.SupplierService;
import com.akarin.erp.mdm.service.WarehouseService;
import com.akarin.erp.purchase.dto.PurchaseOrderCreateReq;
import com.akarin.erp.purchase.dto.PurchaseOrderDetail;
import com.akarin.erp.purchase.dto.PurchaseOrderUpdateReq;
import com.akarin.erp.purchase.entity.PurchaseOrder;
import com.akarin.erp.purchase.entity.PurchaseOrderItem;
import com.akarin.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.akarin.erp.purchase.mapper.PurchaseOrderMapper;
import com.akarin.erp.workflow.service.ApprovalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
        supplierService.requireRef(req.supplierId());
        warehouseService.requireRef(req.warehouseId());
        PurchaseOrder o = new PurchaseOrder();
        o.setOrderNo(NoGenerator.next("PO"));
        o.setSupplierId(req.supplierId());
        o.setWarehouseId(req.warehouseId());
        o.setRemark(req.remark());
        o.setStatus("DRAFT");
        o.setTotalAmount(BigDecimal.ZERO);
        o.setVersion(0);
        save(o);
        BigDecimal total = replaceItems(o.getId(), req.items().stream()
                .map(l -> new Line(l.materialId(), l.qty(), l.price())).toList());
        // 定向回写合计：不整行写回，同时 version + 1
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getId, o.getId())
                .set(PurchaseOrder::getTotalAmount, total)
                .set(PurchaseOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
        return detail(o.getId());
    }

    /** 草稿编辑：WHERE id + 客户端 version，只 SET 可编辑字段；明细整单替换 */
    @Transactional
    public PurchaseOrderDetail update(Long id, PurchaseOrderUpdateReq req) {
        Versions.requirePresent(req.version());
        PurchaseOrder o = require(id);
        if (!"DRAFT".equals(o.getStatus()))
            throw new BizException("仅草稿可编辑，当前: " + o.getStatus());
        supplierService.requireRef(req.supplierId());
        warehouseService.requireRef(req.warehouseId());
        BigDecimal total = replaceItems(id, req.items().stream()
                .map(l -> new Line(l.materialId(), l.qty(), l.price())).toList());
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getId, id).eq(PurchaseOrder::getVersion, req.version())
                .set(PurchaseOrder::getSupplierId, req.supplierId())
                .set(PurchaseOrder::getWarehouseId, req.warehouseId())
                .set(PurchaseOrder::getRemark, req.remark())
                .set(PurchaseOrder::getTotalAmount, total)
                .set(PurchaseOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
        return detail(id);
    }

    public PurchaseOrderDetail detail(Long id) {
        PurchaseOrder o = require(id);
        List<PurchaseOrderItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getOrderId, id));
        return new PurchaseOrderDetail(o, items);
    }

    /** 提交审批：定向改状态 + version + 1，不整行写回 */
    @Transactional
    public Long submit(Long id) {
        PurchaseOrder o = require(id);
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus()))
            throw new BizException("当前状态不可提交: " + o.getStatus());
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getId, id).in(PurchaseOrder::getStatus, "DRAFT", "REJECTED")
                .set(PurchaseOrder::getStatus, "PENDING")
                .set(PurchaseOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
        o = require(id);
        return approvalService.start(BIZ_TYPE, o.getId(), o.getOrderNo(), o.getTotalAmount()).getId();
    }

    /** 审批结果回调：定向改状态 + version + 1，不整行写回 */
    @EventListener
    @Transactional
    public void onApproval(ApprovalFinishedEvent e) {
        if (!BIZ_TYPE.equals(e.bizType())) return;
        String next = e.approved() ? "APPROVED" : "DRAFT";
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getId, e.bizId()).eq(PurchaseOrder::getStatus, "PENDING")
                .set(PurchaseOrder::getStatus, next)
                .set(PurchaseOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
    }

    /** 采购收货：状态条件 + version + 1 */
    @Transactional
    public PurchaseOrderDetail receive(Long id) {
        PurchaseOrderDetail d = detail(id);
        if (!"APPROVED".equals(d.order().getStatus()))
            throw new BizException("仅已审批单据可执行该操作，当前: " + d.order().getStatus());
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getId, id).eq(PurchaseOrder::getStatus, "APPROVED")
                .set(PurchaseOrder::getStatus, "RECEIVED")
                .set(PurchaseOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
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

    private record Line(Long materialId, BigDecimal qty, BigDecimal price) {}

    private BigDecimal replaceItems(Long orderId, List<Line> lines) {
        itemMapper.delete(new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getOrderId, orderId));
        BigDecimal total = BigDecimal.ZERO;
        int lineNo = 0;
        for (var l : lines) {
            lineNo++;
            materialService.requireRef(l.materialId());
            PurchaseOrderItem it = new PurchaseOrderItem();
            it.setOrderId(orderId); it.setMaterialId(l.materialId());
            it.setQty(l.qty()); it.setPrice(l.price());
            it.setAmount(Money.requireFitsAmountColumn(Money.lineAmount(l.qty(), l.price()), "第 " + lineNo + " 行金额"));
            itemMapper.insert(it);
            total = Money.of(total.add(it.getAmount()));
        }
        Money.requireFitsAmountColumn(total, "订单合计金额");
        return total;
    }

    private PurchaseOrder require(Long id) {
        PurchaseOrder o = getById(id);
        if (o == null) throw new BizException(ErrorCode.NOT_FOUND, "单据不存在: " + id);
        return o;
    }
}
