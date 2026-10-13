package com.akarin.erp.common.event;

/** 审批结束事件：业务模块监听后推进单据状态，避免模块间循环依赖 */
public record ApprovalFinishedEvent(String bizType, Long bizId, boolean approved, String approver) {}
