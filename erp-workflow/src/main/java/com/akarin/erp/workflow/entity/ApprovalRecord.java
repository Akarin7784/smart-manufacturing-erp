package com.akarin.erp.workflow.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 审批记录：每个审批人每次操作一条 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_approval_record")
public class ApprovalRecord extends BaseEntity {
    public static final String APPROVE = "APPROVE", REJECT = "REJECT";
    private Long instanceId;
    /** 节点 ID；默认单级审批时为 null */
    private Long nodeId;
    private String nodeName;
    private Integer nodeIndex;
    private String approver;
    private String action;
    private String comment;
}
