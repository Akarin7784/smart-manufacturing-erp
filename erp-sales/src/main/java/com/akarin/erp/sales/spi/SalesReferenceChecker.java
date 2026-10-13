package com.akarin.erp.sales.spi;

import com.akarin.erp.sales.entity.SalesOrder;
import com.akarin.erp.sales.entity.SalesOrderItem;
import com.akarin.erp.sales.mapper.SalesOrderItemMapper;
import com.akarin.erp.sales.mapper.SalesOrderMapper;
import com.akarin.erp.mdm.spi.MasterDataReferenceChecker;
import com.akarin.erp.mdm.spi.MasterDataType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 销售订单对customer/仓库/物料的引用检查 */
@Component
@RequiredArgsConstructor
public class SalesReferenceChecker implements MasterDataReferenceChecker {
    private final SalesOrderMapper orderMapper;
    private final SalesOrderItemMapper itemMapper;

    @Override
    public Optional<String> findReference(MasterDataType type, Long id) {
        boolean used = switch (type) {
            case CUSTOMER -> orderMapper.exists(new LambdaQueryWrapper<SalesOrder>().eq(SalesOrder::getCustomerId, id));
            case WAREHOUSE -> orderMapper.exists(new LambdaQueryWrapper<SalesOrder>().eq(SalesOrder::getWarehouseId, id));
            case MATERIAL -> itemMapper.exists(new LambdaQueryWrapper<SalesOrderItem>().eq(SalesOrderItem::getMaterialId, id));
            default -> false;
        };
        return used ? Optional.of("销售订单") : Optional.empty();
    }
}
