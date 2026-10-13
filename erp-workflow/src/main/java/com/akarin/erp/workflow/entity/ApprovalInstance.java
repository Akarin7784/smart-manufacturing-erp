package com.akarin.erp.workflow.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 审批实例。nodePlan 为发起时按金额条件选出的节点 ID 快照（逗号分隔），为空表示未配置流程的默认单级审批 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wf_approval_instance")
public class ApprovalInstance extends BaseEntity {
    private String bizType;
    private Long bizId;
    private String bizNo;
    private BigDecimal amount;
    /** PENDING / APPROVED / REJECTED */
    private String status;
    private String applicant;
    private String nodePlan;
    private Integer currentIndex;
    /** 最后处理人（兼容原字段） */
    private String approver;
    private String comment;
    private LocalDateTime finishedAt;
    @Version
    private Integer version;
}
