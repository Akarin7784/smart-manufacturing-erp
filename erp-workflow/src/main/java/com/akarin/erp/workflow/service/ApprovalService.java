package com.akarin.erp.workflow.service;

import com.akarin.erp.common.event.ApprovalFinishedEvent;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.util.SecurityUtils;
import com.akarin.erp.workflow.entity.ApprovalInstance;
import com.akarin.erp.workflow.mapper.ApprovalInstanceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ApprovalService extends ServiceImpl<ApprovalInstanceMapper, ApprovalInstance> {
    public static final String PENDING = "PENDING", APPROVED = "APPROVED", REJECTED = "REJECTED";
    private final ApplicationEventPublisher publisher;

    /** 业务单据提交审批 */
    @Transactional
    public ApprovalInstance start(String bizType, Long bizId, String bizNo) {
        boolean pending = exists(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getBizType, bizType).eq(ApprovalInstance::getBizId, bizId)
                .eq(ApprovalInstance::getStatus, PENDING));
        if (pending) throw new BizException("该单据已在审批中");
        ApprovalInstance i = new ApprovalInstance();
        i.setBizType(bizType); i.setBizId(bizId); i.setBizNo(bizNo);
        i.setStatus(PENDING); i.setApplicant(SecurityUtils.currentUsername());
        save(i);
        return i;
    }

    /** 审批通过/驳回，并发布事件让业务模块推进单据状态（同一事务内） */
    @Transactional
    public ApprovalInstance decide(Long id, boolean approved, String comment) {
        ApprovalInstance i = getById(id);
        if (i == null) throw new BizException("审批单不存在");
        if (!PENDING.equals(i.getStatus())) throw new BizException("审批单已处理");
        i.setStatus(approved ? APPROVED : REJECTED);
        i.setApprover(SecurityUtils.currentUsername());
        i.setComment(comment);
        i.setFinishedAt(LocalDateTime.now());
        updateById(i);
        publisher.publishEvent(new ApprovalFinishedEvent(i.getBizType(), i.getBizId(), approved, i.getApprover()));
        return i;
    }
}
