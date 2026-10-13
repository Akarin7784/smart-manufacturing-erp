package com.akarin.erp.inventory.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

/** 即时库存：物料 + 仓库 唯一 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("inv_stock")
public class Stock extends BaseEntity {
    private Long materialId;
    private Long warehouseId;
    private BigDecimal qty;
    /** 乐观锁，防止并发出入库覆盖 */
    @Version
    private Integer version;
}
