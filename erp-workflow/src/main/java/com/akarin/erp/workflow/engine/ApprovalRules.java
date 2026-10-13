package com.akarin.erp.workflow.engine;

import com.akarin.erp.workflow.entity.NodeDef;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** 审批规则（纯函数，便于单测） */
public final class ApprovalRules {
    /** 未配置流程时的默认单级审批：持有该权限点即可审批 */
    public static final String DEFAULT_APPROVE_AUTHORITY = "workflow:approve";
    public static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private ApprovalRules() {}

    /** 按 seq 排序并按金额条件过滤：minAmount 为空总是进入；否则 amount > minAmount 才进入 */
    public static List<NodeDef> applicableNodes(List<NodeDef> nodes, BigDecimal amount) {
        return nodes.stream()
                .filter(n -> n.getMinAmount() == null || (amount != null && amount.compareTo(n.getMinAmount()) > 0))
                .sorted(Comparator.comparing(NodeDef::getSeq))
                .toList();
    }

    /** 节点是否完成：或签 1 人同意即可；会签需要全部 required 人同意（required 至少按 1 计） */
    public static boolean nodeCompleted(String mode, long approvals, int required) {
        if (NodeDef.ANY.equals(mode)) return approvals >= 1;
        return approvals >= Math.max(1, required);
    }

    /** 是否为当前节点审批人：配置节点看角色，默认节点看权限点 */
    public static boolean canApprove(NodeDef node, Set<String> authorities) {
        if (node == null) return authorities.contains(DEFAULT_APPROVE_AUTHORITY) || authorities.contains(ADMIN_AUTHORITY);
        return authorities.contains("ROLE_" + node.getRoleCode());
    }

    /** 会签：只有启动时固化的名单内的人可以审批 */
    public static boolean canApprove(NodeDef node, Set<String> authorities, String username, List<String> countersigners) {
        if (!canApprove(node, authorities)) return false;
        return node == null || !NodeDef.ALL.equals(node.getApproveMode()) || countersigners.contains(username);
    }
}
