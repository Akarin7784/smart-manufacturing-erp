package com.akarin.erp.workflow.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.workflow.dto.DefinitionReq;
import com.akarin.erp.workflow.dto.DefinitionView;
import com.akarin.erp.workflow.entity.ApprovalInstance;
import com.akarin.erp.workflow.entity.NodeDef;
import com.akarin.erp.workflow.entity.ProcessDef;
import com.akarin.erp.workflow.mapper.ApprovalInstanceMapper;
import com.akarin.erp.workflow.mapper.NodeDefMapper;
import com.akarin.erp.workflow.mapper.ProcessDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DefinitionService {
    private final ProcessDefMapper processMapper;
    private final NodeDefMapper nodeMapper;
    private final ApprovalInstanceMapper instanceMapper;

    public List<DefinitionView> list() {
        return processMapper.selectList(new LambdaQueryWrapper<ProcessDef>().orderByAsc(ProcessDef::getBizType))
                .stream().map(p -> new DefinitionView(p, nodes(p.getId()))).toList();
    }

    public DefinitionView get(String bizType) {
        ProcessDef p = find(bizType);
        if (p == null) throw new BizException(ErrorCode.NOT_FOUND, "未配置流程: " + bizType);
        return new DefinitionView(p, nodes(p.getId()));
    }

    /** 启用中的流程定义；未配置或停用返回 null（走默认单级审批） */
    public ProcessDef findEnabled(String bizType) {
        ProcessDef p = find(bizType);
        return p != null && Boolean.TRUE.equals(p.getEnabled()) ? p : null;
    }

    public List<NodeDef> nodes(Long processId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<NodeDef>().eq(NodeDef::getProcessId, processId).orderByAsc(NodeDef::getSeq));
    }

    /** 新建或整体替换流程定义；有审批中的实例时不允许修改，避免节点快照失效 */
    @Transactional
    public DefinitionView save(String bizType, DefinitionReq req) {
        guardNoPending(bizType);
        ProcessDef p = find(bizType);
        if (p == null) {
            p = new ProcessDef();
            p.setBizType(bizType);
        }
        p.setName(req.name());
        p.setEnabled(req.enabled() == null || req.enabled());
        if (p.getId() == null) processMapper.insert(p); else processMapper.updateById(p);
        // 旧节点逻辑删除，保留给历史审批实例查看
        nodeMapper.delete(new LambdaQueryWrapper<NodeDef>().eq(NodeDef::getProcessId, p.getId()));
        int seq = 1;
        for (var n : req.nodes()) {
            NodeDef d = new NodeDef();
            d.setProcessId(p.getId()); d.setSeq(seq++); d.setName(n.name()); d.setRoleCode(n.roleCode());
            d.setApproveMode(n.approveMode()); d.setMinAmount(n.minAmount());
            nodeMapper.insert(d);
        }
        return get(bizType);
    }

    @Transactional
    public void delete(String bizType) {
        guardNoPending(bizType);
        ProcessDef p = find(bizType);
        if (p == null) throw new BizException(ErrorCode.NOT_FOUND, "未配置流程: " + bizType);
        nodeMapper.delete(new LambdaQueryWrapper<NodeDef>().eq(NodeDef::getProcessId, p.getId()));
        processMapper.hardDelete(p.getId());
    }

    private void guardNoPending(String bizType) {
        boolean pending = instanceMapper.exists(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getBizType, bizType).eq(ApprovalInstance::getStatus, ApprovalService.PENDING));
        if (pending) throw new BizException("该单据类型存在审批中的实例，暂不能修改流程");
    }

    private ProcessDef find(String bizType) {
        return processMapper.selectOne(new LambdaQueryWrapper<ProcessDef>().eq(ProcessDef::getBizType, bizType));
    }
}
