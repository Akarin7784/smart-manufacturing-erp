package com.akarin.erp.workflow.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

/** 审批实例（骨架：单级审批；后续扩展为多节点流程定义） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_approval_instance")
public class ApprovalInstance extends BaseEntity {
    private String bizType;
    private Long bizId;
    private String bizNo;
    /** PENDING / APPROVED / REJECTED */
    private String status;
    private String applicant;
    private String approver;
    private String comment;
    private LocalDateTime finishedAt;
}
