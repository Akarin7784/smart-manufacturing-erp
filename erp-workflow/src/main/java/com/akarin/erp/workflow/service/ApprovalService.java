package com.akarin.erp.workflow.service;

import com.akarin.erp.common.event.ApprovalFinishedEvent;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.spi.ApproverDirectory;
import com.akarin.erp.common.util.SecurityUtils;
import com.akarin.erp.workflow.dto.ApprovalDetail;
import com.akarin.erp.workflow.engine.ApprovalRules;
import com.akarin.erp.workflow.entity.ApprovalInstance;
import com.akarin.erp.workflow.entity.ApprovalRecord;
import com.akarin.erp.workflow.entity.NodeDef;
import com.akarin.erp.workflow.entity.ProcessDef;
import com.akarin.erp.workflow.mapper.ApprovalInstanceMapper;
import com.akarin.erp.workflow.mapper.ApprovalRecordMapper;
import com.akarin.erp.workflow.mapper.NodeDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 多级审批引擎。workflow 不依赖任何业务模块：业务方调用 start()，审批结束后通过 ApprovalFinishedEvent 回调。
 */
@Service
@RequiredArgsConstructor
public class ApprovalService extends ServiceImpl<ApprovalInstanceMapper, ApprovalInstance> {
    public static final String PENDING = "PENDING", APPROVED = "APPROVED", REJECTED = "REJECTED";
    private final ApplicationEventPublisher publisher;
    private final DefinitionService definitions;
    private final NodeDefMapper nodeMapper;
    private final ApprovalRecordMapper recordMapper;
    private final ApproverDirectory directory;

    /** 兼容旧调用：无金额 */
    public ApprovalInstance start(String bizType, Long bizId, String bizNo) { return start(bizType, bizId, bizNo, null); }

    /** 业务单据提交审批：按单据类型取流程定义，按金额条件选出节点并快照 */
    @Transactional
    public ApprovalInstance start(String bizType, Long bizId, String bizNo, BigDecimal amount) {
        boolean pending = exists(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getBizType, bizType).eq(ApprovalInstance::getBizId, bizId)
                .eq(ApprovalInstance::getStatus, PENDING));
        if (pending) throw new BizException("该单据已在审批中");
        ApprovalInstance i = new ApprovalInstance();
        i.setBizType(bizType); i.setBizId(bizId); i.setBizNo(bizNo); i.setAmount(amount);
        i.setStatus(PENDING); i.setApplicant(SecurityUtils.currentUsername());
        i.setCurrentIndex(0); i.setVersion(0);
        ProcessDef def = definitions.findEnabled(bizType);
        if (def != null) {
            List<NodeDef> plan = ApprovalRules.applicableNodes(definitions.nodes(def.getId()), amount);
            if (plan.isEmpty()) throw new BizException("流程 " + bizType + " 没有适用于该金额的审批节点");
            i.setNodePlan(String.join(",", plan.stream().map(n -> String.valueOf(n.getId())).toList()));
        }
        save(i);
        return i;
    }

    /** 审批通过/驳回。只有当前节点的审批人能处理；同一人同一节点只能处理一次 */
    @Transactional
    public ApprovalInstance decide(Long id, boolean approved, String comment) {
        ApprovalInstance i = getById(id);
        if (i == null) throw new BizException(ErrorCode.NOT_FOUND, "审批单不存在");
        if (!PENDING.equals(i.getStatus())) throw new BizException("审批单已处理");
        String me = SecurityUtils.currentUsername();
        NodeDef node = currentNode(i);
        if (!ApprovalRules.canApprove(node, SecurityUtils.currentAuthorities()))
            throw new BizException(ErrorCode.FORBIDDEN, "不是当前节点审批人");
        boolean acted = recordMapper.exists(recordsAtCurrentNode(i).eq(ApprovalRecord::getApprover, me));
        if (acted) throw new BizException("你已处理过当前节点");

        ApprovalRecord r = new ApprovalRecord();
        r.setInstanceId(i.getId()); r.setNodeIndex(i.getCurrentIndex());
        r.setNodeId(node == null ? null : node.getId());
        r.setNodeName(node == null ? "默认审批" : node.getName());
        r.setApprover(me); r.setAction(approved ? ApprovalRecord.APPROVE : ApprovalRecord.REJECT); r.setComment(comment);
        recordMapper.insert(r);

        i.setApprover(me);
        i.setComment(comment);
        boolean finished;
        if (!approved) {
            i.setStatus(REJECTED);
            finished = true;
        } else {
            long approvals = recordMapper.selectCount(recordsAtCurrentNode(i).eq(ApprovalRecord::getAction, ApprovalRecord.APPROVE));
            String mode = node == null ? NodeDef.ANY : node.getApproveMode();
            int required = node == null ? 1 : directory.usernamesWithRole(node.getRoleCode()).size();
            if (!ApprovalRules.nodeCompleted(mode, approvals, required)) {
                finished = false;
            } else if (i.getCurrentIndex() + 1 < planIds(i).size()) {
                i.setCurrentIndex(i.getCurrentIndex() + 1);
                finished = false;
            } else {
                i.setStatus(APPROVED);
                finished = true;
            }
        }
        if (finished) i.setFinishedAt(LocalDateTime.now());
        if (!updateById(i)) throw new BizException(ErrorCode.CONCURRENT_MODIFICATION);
        if (finished) publisher.publishEvent(new ApprovalFinishedEvent(i.getBizType(), i.getBizId(), approved, me));
        return i;
    }

    /** 待我审批：当前节点我有资格且我尚未处理 */
    public List<ApprovalInstance> todo() {
        Set<String> auth = SecurityUtils.currentAuthorities();
        String me = SecurityUtils.currentUsername();
        return list(new LambdaQueryWrapper<ApprovalInstance>().eq(ApprovalInstance::getStatus, PENDING).orderByAsc(ApprovalInstance::getId))
                .stream()
                .filter(i -> ApprovalRules.canApprove(currentNode(i), auth))
                .filter(i -> !recordMapper.exists(recordsAtCurrentNode(i).eq(ApprovalRecord::getApprover, me)))
                .toList();
    }

    public ApprovalDetail detail(Long id) {
        ApprovalInstance i = getById(id);
        if (i == null) throw new BizException(ErrorCode.NOT_FOUND, "审批单不存在");
        List<NodeDef> plan = planIds(i).stream().map(nodeMapper::selectByIdIncludingDeleted).filter(Objects::nonNull).toList();
        NodeDef current = PENDING.equals(i.getStatus()) ? currentNode(i) : null;
        List<ApprovalRecord> records = recordMapper.selectList(new LambdaQueryWrapper<ApprovalRecord>()
                .eq(ApprovalRecord::getInstanceId, id).orderByAsc(ApprovalRecord::getId));
        return new ApprovalDetail(i, plan, current, records);
    }

    public List<ApprovalInstance> history(String bizType, Long bizId) {
        return list(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(StringUtils.hasText(bizType), ApprovalInstance::getBizType, bizType)
                .eq(bizId != null, ApprovalInstance::getBizId, bizId)
                .orderByDesc(ApprovalInstance::getId));
    }

    private LambdaQueryWrapper<ApprovalRecord> recordsAtCurrentNode(ApprovalInstance i) {
        return new LambdaQueryWrapper<ApprovalRecord>().eq(ApprovalRecord::getInstanceId, i.getId())
                .eq(ApprovalRecord::getNodeIndex, i.getCurrentIndex());
    }

    private static List<Long> planIds(ApprovalInstance i) {
        if (!StringUtils.hasText(i.getNodePlan())) return List.of();
        return Arrays.stream(i.getNodePlan().split(",")).map(Long::valueOf).toList();
    }

    /** 当前节点；默认单级审批返回 null */
    private NodeDef currentNode(ApprovalInstance i) {
        List<Long> ids = planIds(i);
        if (ids.isEmpty()) return null;
        NodeDef n = nodeMapper.selectById(ids.get(i.getCurrentIndex()));
        if (n == null) throw new BizException("流程节点已被删除，请联系管理员");
        return n;
    }
}
