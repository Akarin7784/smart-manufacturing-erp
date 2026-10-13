package com.akarin.erp.purchase.spi;

import com.akarin.erp.purchase.entity.PurchaseOrder;
import com.akarin.erp.purchase.entity.PurchaseOrderItem;
import com.akarin.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.akarin.erp.purchase.mapper.PurchaseOrderMapper;
import com.akarin.erp.mdm.spi.MasterDataReferenceChecker;
import com.akarin.erp.mdm.spi.MasterDataType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 采购订单对supplier/仓库/物料的引用检查 */
@Component
@RequiredArgsConstructor
public class PurchaseReferenceChecker implements MasterDataReferenceChecker {
    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper itemMapper;

    @Override
    public Optional<String> findReference(MasterDataType type, Long id) {
        boolean used = switch (type) {
            case SUPPLIER -> orderMapper.exists(new LambdaQueryWrapper<PurchaseOrder>().eq(PurchaseOrder::getSupplierId, id));
            case WAREHOUSE -> orderMapper.exists(new LambdaQueryWrapper<PurchaseOrder>().eq(PurchaseOrder::getWarehouseId, id));
            case MATERIAL -> itemMapper.exists(new LambdaQueryWrapper<PurchaseOrderItem>().eq(PurchaseOrderItem::getMaterialId, id));
            default -> false;
        };
        return used ? Optional.of("采购订单") : Optional.empty();
    }
}
