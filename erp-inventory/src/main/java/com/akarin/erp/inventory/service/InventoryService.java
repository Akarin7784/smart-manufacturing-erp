package com.akarin.erp.inventory.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.util.NoGenerator;
import com.akarin.erp.common.util.Versions;
import com.akarin.erp.inventory.entity.Stock;
import com.akarin.erp.inventory.entity.StockTxn;
import com.akarin.erp.inventory.mapper.StockMapper;
import com.akarin.erp.inventory.mapper.StockTxnMapper;
import com.akarin.erp.mdm.service.MaterialService;
import com.akarin.erp.mdm.service.WarehouseService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class InventoryService {
    /** 库存流水号前缀（#18 b）：SMI 入库 / SMO 出库。原来的 SI / SO 中 SO 与销售单号前缀相同；历史流水号保持不变 */
    public static final String TXN_PREFIX_IN = "SMI";
    public static final String TXN_PREFIX_OUT = "SMO";
    private final StockMapper stockMapper;
    private final StockTxnMapper txnMapper;
    private final MaterialService materialService;
    private final WarehouseService warehouseService;

    @Transactional
    public StockTxn stockIn(Long materialId, Long warehouseId, BigDecimal qty, String bizType, String bizNo, String remark) {
        return change("IN", materialId, warehouseId, qty, bizType, bizNo, remark);
    }

    @Transactional
    public StockTxn stockOut(Long materialId, Long warehouseId, BigDecimal qty, String bizType, String bizNo, String remark) {
        return change("OUT", materialId, warehouseId, qty, bizType, bizNo, remark);
    }

    /** 一条库存变动：物料 + 仓库 + 数量 */
    public record StockLine(Long materialId, Long warehouseId, BigDecimal qty) {}

    private static final Comparator<StockLine> LOCK_ORDER =
            Comparator.comparing(StockLine::materialId).thenComparing(StockLine::warehouseId);

    /**
     * 单据整单入库（采购收货）。同一 (物料, 仓库) 的多行先合并数量，再按 (materialId, warehouseId) 升序逐个加锁处理：
     * 所有事务以相同顺序获取库存行锁，[A,B] 与 [B,A] 两张单并发时不会互相等待成环（死锁）。
     * 流水按合并后的 (物料, 仓库) 各记一条（数量为合计），与库存行的一次变动一一对应。
     */
    @Transactional
    public List<StockTxn> stockInAll(List<StockLine> lines, String bizType, String bizNo) {
        return applyAll("IN", lines, bizType, bizNo);
    }

    /** 单据整单出库（销售发货），规则同 {@link #stockInAll} */
    @Transactional
    public List<StockTxn> stockOutAll(List<StockLine> lines, String bizType, String bizNo) {
        return applyAll("OUT", lines, bizType, bizNo);
    }

    /** 合并同 (物料, 仓库) 并排序；仅供本类与测试使用 */
    static List<StockLine> mergeAndSort(List<StockLine> lines) {
        Map<StockLine, BigDecimal> sum = new TreeMap<>(LOCK_ORDER);
        for (StockLine l : lines) sum.merge(new StockLine(l.materialId(), l.warehouseId(), null), l.qty(), BigDecimal::add);
        List<StockLine> out = new ArrayList<>(sum.size());
        sum.forEach((k, q) -> out.add(new StockLine(k.materialId(), k.warehouseId(), q)));
        return out;
    }

    private List<StockTxn> applyAll(String dir, List<StockLine> lines, String bizType, String bizNo) {
        List<StockTxn> out = new ArrayList<>();
        for (StockLine l : mergeAndSort(lines))
            out.add(change(dir, l.materialId(), l.warehouseId(), l.qty(), bizType, bizNo, null));
        return out;
    }

    private StockTxn change(String dir, Long materialId, Long warehouseId, BigDecimal qty,
                            String bizType, String bizNo, String remark) {
        if (qty == null || qty.signum() <= 0) throw new BizException("数量必须大于 0");
        materialService.requireRef(materialId);
        warehouseService.requireRef(warehouseId);
        // 入库：先保证库存行存在（幂等 upsert，首次并发收货不再撞唯一键）；出库：没有行即库存不足
        if ("IN".equals(dir)) stockMapper.ensureRow(materialId, warehouseId);
        // 加锁当前读：同一 (物料, 仓库) 的并发收发在这里串行化，读到最新已提交数量（MySQL RR 下不受快照影响）
        Stock s = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                .eq(Stock::getMaterialId, materialId).eq(Stock::getWarehouseId, warehouseId)
                .last("FOR UPDATE"));
        if (s == null) throw new BizException("库存不足");
        BigDecimal after = "IN".equals(dir) ? s.getQty().add(qty) : s.getQty().subtract(qty);
        if (after.signum() < 0) throw new BizException("库存不足，当前可用: " + s.getQty());
        String over = com.akarin.erp.common.util.Money.scaleError(after, 14, 4, "入库后库存数量");
        if (over != null) throw new BizException(over + "，超出数量上限");
        s.setQty(after);
        // 行已加锁，version 不会被别人改；仍按乐观锁写并要求恰好 1 行，防御性兜底
        Versions.requireOneRow(stockMapper.updateById(s));
        StockTxn t = new StockTxn();
        t.setTxnNo(NoGenerator.next("IN".equals(dir) ? TXN_PREFIX_IN : TXN_PREFIX_OUT));
        t.setDirection(dir); t.setMaterialId(materialId); t.setWarehouseId(warehouseId);
        t.setQty(qty); t.setBalanceAfter(after);
        t.setBizType(bizType == null ? "MANUAL" : bizType); t.setBizNo(bizNo); t.setRemark(remark);
        txnMapper.insert(t);
        return t;
    }
}
