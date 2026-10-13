package com.akarin.erp.workflow.dto;

import com.akarin.erp.workflow.entity.ApprovalInstance;
import com.akarin.erp.workflow.entity.ApprovalRecord;
import com.akarin.erp.workflow.entity.NodeDef;
import java.util.List;

/** 审批详情：实例、计划节点（已按金额条件过滤）、当前节点、审批记录 */
public record ApprovalDetail(ApprovalInstance instance, List<NodeDef> plan, NodeDef currentNode, List<ApprovalRecord> records) {}
