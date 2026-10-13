package com.akarin.erp.workflow.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

/** 流程节点：按 seq 顺序执行；审批人按角色；ALL=会签，ANY=或签；minAmount 非空时仅当单据金额 > minAmount 才进入该节点 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_node_def")
public class NodeDef extends BaseEntity {
    public static final String ALL = "ALL", ANY = "ANY";
    private Long processId;
    private Integer seq;
    private String name;
    private String roleCode;
    private String approveMode;
    private BigDecimal minAmount;
}
