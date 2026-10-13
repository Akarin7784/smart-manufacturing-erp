package com.akarin.erp.mdm.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.akarin.erp.mdm.spi.MasterData;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mdm_warehouse")
public class Warehouse extends BaseEntity implements MasterData {
    private String code;
    private String name;
    private String address;
    private Integer status;
}
