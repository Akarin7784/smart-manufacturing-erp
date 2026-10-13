package com.akarin.erp.mdm.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mdm_supplier")
public class Supplier extends BaseEntity {
    private String code;
    private String name;
    private String contact;
    private String phone;
    private Integer status;
}
