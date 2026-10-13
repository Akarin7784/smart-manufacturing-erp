package com.akarin.erp.inventory.spi;

import com.akarin.erp.inventory.entity.Stock;
import com.akarin.erp.inventory.entity.StockTxn;
import com.akarin.erp.inventory.mapper.StockMapper;
import com.akarin.erp.inventory.mapper.StockTxnMapper;
import com.akarin.erp.mdm.spi.MasterDataReferenceChecker;
import com.akarin.erp.mdm.spi.MasterDataType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 库存 / 库存流水对物料、仓库的引用检查 */
@Component
@RequiredArgsConstructor
public class InventoryReferenceChecker implements MasterDataReferenceChecker {
    private final StockMapper stockMapper;
    private final StockTxnMapper txnMapper;

    @Override
    public Optional<String> findReference(MasterDataType type, Long id) {
        boolean used = switch (type) {
            case MATERIAL -> txnMapper.exists(new LambdaQueryWrapper<StockTxn>().eq(StockTxn::getMaterialId, id))
                    || stockMapper.exists(new LambdaQueryWrapper<Stock>().eq(Stock::getMaterialId, id));
            case WAREHOUSE -> txnMapper.exists(new LambdaQueryWrapper<StockTxn>().eq(StockTxn::getWarehouseId, id))
                    || stockMapper.exists(new LambdaQueryWrapper<Stock>().eq(Stock::getWarehouseId, id));
            default -> false;
        };
        return used ? Optional.of("库存流水") : Optional.empty();
    }
}
