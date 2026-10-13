package com.akarin.erp.workflow.engine;

import com.akarin.erp.workflow.entity.NodeDef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRulesTest {
    static NodeDef node(long id, int seq, String role, String mode, String min) {
        NodeDef n = new NodeDef();
        n.setId(id); n.setSeq(seq); n.setRoleCode(role); n.setApproveMode(mode);
        n.setMinAmount(min == null ? null : new BigDecimal(min));
        return n;
    }

    @Test void conditionalNodeIncludedOnlyAboveThreshold() {
        var nodes = List.of(node(3, 3, "GM", NodeDef.ANY, "1000"), node(1, 1, "MGR", NodeDef.ANY, null), node(2, 2, "FIN", NodeDef.ALL, null));
        assertThat(ApprovalRules.applicableNodes(nodes, new BigDecimal("1000"))).extracting(NodeDef::getId).containsExactly(1L, 2L);
        assertThat(ApprovalRules.applicableNodes(nodes, new BigDecimal("1000.01"))).extracting(NodeDef::getId).containsExactly(1L, 2L, 3L);
        assertThat(ApprovalRules.applicableNodes(nodes, null)).extracting(NodeDef::getId).containsExactly(1L, 2L);
    }

    @Test void orSignCompletesWithOneApproval() {
        assertThat(ApprovalRules.nodeCompleted(NodeDef.ANY, 0, 3)).isFalse();
        assertThat(ApprovalRules.nodeCompleted(NodeDef.ANY, 1, 3)).isTrue();
    }

    @Test void countersignNeedsEveryone() {
        assertThat(ApprovalRules.nodeCompleted(NodeDef.ALL, 1, 2)).isFalse();
        assertThat(ApprovalRules.nodeCompleted(NodeDef.ALL, 2, 2)).isTrue();
        // 角色下没有可用审批人时按 1 人处理，防止 0 人自动通过
        assertThat(ApprovalRules.nodeCompleted(NodeDef.ALL, 0, 0)).isFalse();
        assertThat(ApprovalRules.nodeCompleted(NodeDef.ALL, 1, 0)).isTrue();
    }

    @Test void onlyCurrentNodeRoleMayApprove() {
        NodeDef n = node(1, 1, "MGR", NodeDef.ANY, null);
        assertThat(ApprovalRules.canApprove(n, Set.of("ROLE_MGR"))).isTrue();
        assertThat(ApprovalRules.canApprove(n, Set.of("ROLE_FIN", "workflow:approve"))).as("越权").isFalse();
        assertThat(ApprovalRules.canApprove(null, Set.of("workflow:approve"))).as("默认单级审批").isTrue();
        assertThat(ApprovalRules.canApprove(null, Set.of("ROLE_MGR"))).isFalse();
    }
}
