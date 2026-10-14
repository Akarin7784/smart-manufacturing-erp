package com.akarin.erp.workflow.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 流程定义：一个单据类型一条 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_process_def")
public class ProcessDef extends BaseEntity {
    private String bizType;
    private String name;
    private Boolean enabled;
    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;
}
