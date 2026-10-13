package com.akarin.erp.inventory.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.util.NoGenerator;
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

@Service
@RequiredArgsConstructor
public class InventoryService {
    private static final int MAX_RETRY = 3;
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

    private StockTxn change(String dir, Long materialId, Long warehouseId, BigDecimal qty,
                            String bizType, String bizNo, String remark) {
        if (qty == null || qty.signum() <= 0) throw new BizException("数量必须大于 0");
        materialService.requireRef(materialId);
        warehouseService.requireRef(warehouseId);
        for (int i = 0; i < MAX_RETRY; i++) {
            Stock s = stockMapper.selectOne(new LambdaQueryWrapper<Stock>()
                    .eq(Stock::getMaterialId, materialId).eq(Stock::getWarehouseId, warehouseId));
            if (s == null) {
                if ("OUT".equals(dir)) throw new BizException("库存不足");
                s = new Stock();
                s.setMaterialId(materialId); s.setWarehouseId(warehouseId);
                s.setQty(BigDecimal.ZERO); s.setVersion(0);
                stockMapper.insert(s);
            }
            BigDecimal after = "IN".equals(dir) ? s.getQty().add(qty) : s.getQty().subtract(qty);
            if (after.signum() < 0) throw new BizException("库存不足，当前可用: " + s.getQty());
            String over = com.akarin.erp.common.util.Money.scaleError(after, 14, 4, "入库后库存数量");
            if (over != null) throw new BizException(over + "，超出数量上限");
            s.setQty(after);
            if (stockMapper.updateById(s) == 1) {
                StockTxn t = new StockTxn();
                t.setTxnNo(NoGenerator.next("IN".equals(dir) ? "SI" : "SO"));
                t.setDirection(dir); t.setMaterialId(materialId); t.setWarehouseId(warehouseId);
                t.setQty(qty); t.setBalanceAfter(after);
                t.setBizType(bizType == null ? "MANUAL" : bizType); t.setBizNo(bizNo); t.setRemark(remark);
                txnMapper.insert(t);
                return t;
            }
            // 版本冲突，重读后重试
        }
        throw new BizException("库存并发冲突，请重试");
    }
}
