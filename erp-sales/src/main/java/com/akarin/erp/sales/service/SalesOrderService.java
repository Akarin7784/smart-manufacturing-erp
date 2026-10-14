package com.akarin.erp.sales.service;

import com.akarin.erp.common.event.ApprovalFinishedEvent;
import com.akarin.erp.common.event.StockBizCompletedEvent;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.util.Money;
import com.akarin.erp.common.util.NoGenerator;
import com.akarin.erp.common.util.Versions;
import com.akarin.erp.inventory.service.InventoryService;
import com.akarin.erp.mdm.service.MaterialService;
import com.akarin.erp.mdm.service.CustomerService;
import com.akarin.erp.mdm.service.WarehouseService;
import com.akarin.erp.sales.dto.SalesOrderCreateReq;
import com.akarin.erp.sales.dto.SalesOrderDetail;
import com.akarin.erp.sales.dto.SalesOrderUpdateReq;
import com.akarin.erp.sales.entity.SalesOrder;
import com.akarin.erp.sales.entity.SalesOrderItem;
import com.akarin.erp.sales.mapper.SalesOrderItemMapper;
import com.akarin.erp.sales.mapper.SalesOrderMapper;
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
        o.setVersion(0);
        save(o);
        BigDecimal total = replaceItems(o.getId(), req.items().stream()
                .map(l -> new Line(l.materialId(), l.qty(), l.price())).toList());
        // 定向回写合计：不整行写回，同时 version + 1
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<SalesOrder>()
                .eq(SalesOrder::getId, o.getId())
                .set(SalesOrder::getTotalAmount, total)
                .set(SalesOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
        return detail(o.getId());
    }

    /** 草稿编辑：WHERE id + 客户端 version，只 SET 可编辑字段；明细整单替换 */
    @Transactional
    public SalesOrderDetail update(Long id, SalesOrderUpdateReq req) {
        Versions.requirePresent(req.version());
        SalesOrder o = require(id);
        if (!"DRAFT".equals(o.getStatus()))
            throw new BizException("仅草稿可编辑，当前: " + o.getStatus());
        customerService.requireRef(req.customerId());
        warehouseService.requireRef(req.warehouseId());
        BigDecimal total = replaceItems(id, req.items().stream()
                .map(l -> new Line(l.materialId(), l.qty(), l.price())).toList());
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<SalesOrder>()
                .eq(SalesOrder::getId, id).eq(SalesOrder::getVersion, req.version())
                .set(SalesOrder::getCustomerId, req.customerId())
                .set(SalesOrder::getWarehouseId, req.warehouseId())
                .set(SalesOrder::getRemark, req.remark())
                .set(SalesOrder::getTotalAmount, total)
                .set(SalesOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
        return detail(id);
    }

    public SalesOrderDetail detail(Long id) {
        SalesOrder o = require(id);
        List<SalesOrderItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>().eq(SalesOrderItem::getOrderId, id));
        return new SalesOrderDetail(o, items);
    }

    /** 提交审批：定向改状态 + version + 1，不整行写回 */
    @Transactional
    public Long submit(Long id) {
        SalesOrder o = require(id);
        if (!"DRAFT".equals(o.getStatus()) && !"REJECTED".equals(o.getStatus()))
            throw new BizException("当前状态不可提交: " + o.getStatus());
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<SalesOrder>()
                .eq(SalesOrder::getId, id).in(SalesOrder::getStatus, "DRAFT", "REJECTED")
                .set(SalesOrder::getStatus, "PENDING")
                .set(SalesOrder::getUpdatedAt, LocalDateTime.now())
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
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<SalesOrder>()
                .eq(SalesOrder::getId, e.bizId()).eq(SalesOrder::getStatus, "PENDING")
                .set(SalesOrder::getStatus, next)
                .set(SalesOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
    }

    /** 销售发货：状态条件 + version + 1 */
    @Transactional
    public SalesOrderDetail ship(Long id) {
        SalesOrderDetail d = detail(id);
        if (!"APPROVED".equals(d.order().getStatus()))
            throw new BizException("仅已审批单据可执行该操作，当前: " + d.order().getStatus());
        Versions.requireOneRow(baseMapper.update(null, new LambdaUpdateWrapper<SalesOrder>()
                .eq(SalesOrder::getId, id).eq(SalesOrder::getStatus, "APPROVED")
                .set(SalesOrder::getStatus, "SHIPPED")
                .set(SalesOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1")));
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

    private record Line(Long materialId, BigDecimal qty, BigDecimal price) {}

    private BigDecimal replaceItems(Long orderId, List<Line> lines) {
        itemMapper.delete(new LambdaQueryWrapper<SalesOrderItem>().eq(SalesOrderItem::getOrderId, orderId));
        BigDecimal total = BigDecimal.ZERO;
        int lineNo = 0;
        for (var l : lines) {
            lineNo++;
            materialService.requireRef(l.materialId());
            SalesOrderItem it = new SalesOrderItem();
            it.setOrderId(orderId); it.setMaterialId(l.materialId());
            it.setQty(l.qty()); it.setPrice(l.price());
            it.setAmount(Money.requireFitsAmountColumn(Money.lineAmount(l.qty(), l.price()), "第 " + lineNo + " 行金额"));
            itemMapper.insert(it);
            total = Money.of(total.add(it.getAmount()));
        }
        Money.requireFitsAmountColumn(total, "订单合计金额");
        return total;
    }

    private SalesOrder require(Long id) {
        SalesOrder o = getById(id);
        if (o == null) throw new BizException(ErrorCode.NOT_FOUND, "单据不存在: " + id);
        return o;
    }
}
