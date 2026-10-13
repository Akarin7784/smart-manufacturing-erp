package com.akarin.erp.mdm.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.akarin.erp.mdm.spi.MasterData;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mdm_customer")
public class Customer extends BaseEntity implements MasterData {
    private String code;
    private String name;
    private String contact;
    private String phone;
    private Integer status;
}
