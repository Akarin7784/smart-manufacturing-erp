package com.akarin.erp.workflow.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.workflow.dto.ApprovalDetail;
import com.akarin.erp.workflow.dto.DefinitionReq;
import com.akarin.erp.workflow.dto.DefinitionView;
import com.akarin.erp.workflow.entity.ApprovalInstance;
import com.akarin.erp.workflow.service.ApprovalService;
import com.akarin.erp.workflow.service.DefinitionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class ApprovalController {
    private final ApprovalService service;
    private final DefinitionService definitions;

    public record DecideReq(boolean approved, String comment) {}

    /** 全部审批中的实例（管理视角） */
    @GetMapping("/approvals/pending")
    @PreAuthorize("hasAuthority('workflow:admin')")
    public R<List<ApprovalInstance>> pending() {
        return R.ok(service.list(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getStatus, ApprovalService.PENDING).orderByAsc(ApprovalInstance::getId)));
    }

    /** 待我审批 */
    @GetMapping("/approvals/todo")
    public R<List<ApprovalInstance>> todo() { return R.ok(service.todo()); }

    /** 按单据查审批历史 */
    @GetMapping("/approvals")
    public R<List<ApprovalInstance>> history(@RequestParam(required = false) String bizType, @RequestParam(required = false) Long bizId) {
        return R.ok(service.history(bizType, bizId));
    }

    @GetMapping("/approvals/{id}")
    public R<ApprovalDetail> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    /** 审批：资格在服务层按当前节点校验（角色或默认权限点 workflow:approve） */
    @PostMapping("/approvals/{id}/decide")
    public R<ApprovalInstance> decide(@PathVariable Long id, @RequestBody DecideReq req) {
        return R.ok(service.decide(id, req.approved(), req.comment()));
    }

    @GetMapping("/definitions")
    @PreAuthorize("hasAuthority('workflow:admin')")
    public R<List<DefinitionView>> definitions() { return R.ok(definitions.list()); }

    @GetMapping("/definitions/{bizType}")
    @PreAuthorize("hasAuthority('workflow:admin')")
    public R<DefinitionView> definition(@PathVariable String bizType) { return R.ok(definitions.get(bizType)); }

    @PutMapping("/definitions/{bizType}")
    @PreAuthorize("hasAuthority('workflow:admin')")
    public R<DefinitionView> save(@PathVariable String bizType, @Valid @RequestBody DefinitionReq req) {
        return R.ok(definitions.save(bizType, req));
    }

    @DeleteMapping("/definitions/{bizType}")
    @PreAuthorize("hasAuthority('workflow:admin')")
    public R<Void> delete(@PathVariable String bizType) { definitions.delete(bizType); return R.ok(); }
}
