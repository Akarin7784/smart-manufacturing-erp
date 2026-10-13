package com.akarin.erp.mdm.spi;

import java.util.Optional;

/**
 * 主数据引用检查 SPI。mdm 不依赖业务模块；业务模块（采购/销售/库存/财务）实现本接口并注册为 Spring Bean，
 * 删除主数据前逐个询问，有引用则拒绝删除（只能停用）。
 */
public interface MasterDataReferenceChecker {
    /** @return 若被引用，返回引用方描述（如"采购订单"）；否则 empty */
    Optional<String> findReference(MasterDataType type, Long id);
}
