package com.akarin.erp.mdm.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.akarin.erp.mdm.spi.MasterData;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mdm_material")
public class Material extends BaseEntity implements MasterData {
    private String code;
    private String name;
    private String spec;
    private String unit;
    private String category;
    private Integer status;
}
