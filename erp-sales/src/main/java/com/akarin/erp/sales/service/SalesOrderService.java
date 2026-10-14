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

    /** 创建前的输入校验，不开事务、不取号（#22：先校验，再取号，再拿业务连接/开事务） */
    public void validateCreate(SalesOrderCreateReq req) {
        customerService.requireRef(req.customerId());
        warehouseService.requireRef(req.warehouseId());
        totalOf(buildItems(req.items().stream().map(l -> new Line(l.materialId(), l.qty(), l.price())).toList()));
    }

    /**
     * ship 需要的单号（事务外预取，#22）：库存流水每个合并后的物料一个，加一个AR单号。
     * 先判状态，非 APPROVED 直接 400，不浪费号；事务内仍会再判一次。
     */
    public java.util.Map<String, Integer> shipNumbers(Long id) {
        SalesOrderDetail d = detail(id);
        if (!"APPROVED".equals(d.order().getStatus()))
            throw new BizException("仅已审批单据可执行该操作，当前: " + d.order().getStatus());
        int n = (int) d.items().stream().map(SalesOrderItem::getMaterialId).distinct().count();
        return java.util.Map.of(InventoryService.TXN_PREFIX_OUT, n, "AR", 1);
    }

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
        // 先校验明细、算好合计再一次性插入：不再回写合计，新单 version = 0
        List<SalesOrderItem> items = buildItems(req.items().stream()
                .map(l -> new Line(l.materialId(), l.qty(), l.price())).toList());
        o.setTotalAmount(totalOf(items));
        o.setVersion(0);
        save(o);
        insertItems(o.getId(), items);
        return detail(o.getId());
    }

    /** 草稿编辑：WHERE id + 客户端 version，只 SET 可编辑字段；明细整单替换 */
    @Transactional
    public SalesOrderDetail update(Long id, SalesOrderUpdateReq req) {
        SalesOrder o = require(id);              // 先判存在：不存在 404
        Versions.requirePresent(req.version());     // 再判 version：缺失 400
        // 引用检查（供应商、仓库、物料）属于输入校验，先于状态判断：非草稿订单引用不存在的对象也返回 400
        customerService.requireRef(req.customerId());
        warehouseService.requireRef(req.warehouseId());
        List<SalesOrderItem> items = buildItems(req.items().stream()
                .map(l -> new Line(l.materialId(), l.qty(), l.price())).toList());
        requireDraft(o);
        BigDecimal total = totalOf(items);
        itemMapper.delete(new LambdaQueryWrapper<SalesOrderItem>().eq(SalesOrderItem::getOrderId, id));
        insertItems(id, items);
        int rows = baseMapper.update(null, new LambdaUpdateWrapper<SalesOrder>()
                .eq(SalesOrder::getId, id).eq(SalesOrder::getVersion, req.version())
                .eq(SalesOrder::getStatus, "DRAFT")
                .set(SalesOrder::getCustomerId, req.customerId())
                .set(SalesOrder::getWarehouseId, req.warehouseId())
                .set(SalesOrder::getRemark, req.remark())
                .set(SalesOrder::getTotalAmount, total)
                .set(SalesOrder::getUpdatedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        if (rows != 1) {
            // 0 行：重读区分原因——已不是草稿（被并发提交/审批）→ 409/40903；否则 version 过期 → 40900。事务回滚，明细替换一并撤销
            // 必须加锁读：MySQL REPEATABLE READ 下普通 SELECT 读的是本事务开头 require(id) 建立的快照，
            // 会把已被并发提交的订单仍看成 DRAFT 而误报 40900；FOR UPDATE 是当前读，拿到最新已提交的状态（H2 同样支持）
            requireDraft(requireLatest(id));
            Versions.requireOneRow(rows);
        }
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
        // 合并同物料行、按 (materialId, warehouseId) 固定顺序加锁，避免正反序单据并发死锁；流水按合并后的物料各一条
        inventoryService.stockOutAll(d.items().stream()
                .map(it -> new com.akarin.erp.inventory.service.InventoryService.StockLine(
                        it.getMaterialId(), d.order().getWarehouseId(), it.getQty()))
                .toList(), "SALES_SHIPMENT", d.order().getOrderNo());
        BigDecimal amount = Money.sum(d.items().stream().map(i -> Money.lineAmount(i.getQty(), i.getPrice())).toList());
        publisher.publishEvent(new StockBizCompletedEvent(StockBizCompletedEvent.SALES_SHIPMENT,
                d.order().getOrderNo(), d.order().getCustomerId(), amount));
        return detail(id);   // 重新读取：返回的 status / version 与库里一致
    }

    private record Line(Long materialId, BigDecimal qty, BigDecimal price) {}

    /** 校验物料并计算每行金额（不落库） */
    private List<SalesOrderItem> buildItems(List<Line> lines) {
        List<SalesOrderItem> items = new java.util.ArrayList<>();
        int lineNo = 0;
        for (var l : lines) {
            lineNo++;
            materialService.requireRef(l.materialId());
            SalesOrderItem it = new SalesOrderItem();
            it.setMaterialId(l.materialId());
            it.setQty(l.qty()); it.setPrice(l.price());   // 单价原样保存（最多 4 位小数）
            it.setAmount(Money.requireFitsAmountColumn(Money.lineAmount(l.qty(), l.price()), "第 " + lineNo + " 行金额"));
            items.add(it);
        }
        return items;
    }

    private BigDecimal totalOf(List<SalesOrderItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (var it : items) total = Money.of(total.add(it.getAmount()));
        return Money.requireFitsAmountColumn(total, "订单合计金额");
    }

    private void insertItems(Long orderId, List<SalesOrderItem> items) {
        for (var it : items) { it.setOrderId(orderId); itemMapper.insert(it); }
    }

    private void requireDraft(SalesOrder o) {
        if (!"DRAFT".equals(o.getStatus())) throw new BizException(ErrorCode.ORDER_STATUS_CHANGED, ErrorCode.ORDER_STATUS_CHANGED.getDefaultMsg());   // 不是草稿 → 409/40903
    }

    /** 加锁当前读（SELECT ... FOR UPDATE），绕过 RR 快照 */
    private SalesOrder requireLatest(Long id) {
        SalesOrder o = baseMapper.selectOne(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getId, id).last("FOR UPDATE"));
        if (o == null) throw new BizException(ErrorCode.NOT_FOUND, "单据不存在: " + id);
        return o;
    }

    private SalesOrder require(Long id) {
        SalesOrder o = getById(id);
        if (o == null) throw new BizException(ErrorCode.NOT_FOUND, "单据不存在: " + id);
        return o;
    }
}
