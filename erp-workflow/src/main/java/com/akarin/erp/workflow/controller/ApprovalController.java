package com.akarin.erp.workflow.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.workflow.entity.ApprovalInstance;
import com.akarin.erp.workflow.service.ApprovalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow/approvals")
@RequiredArgsConstructor
public class ApprovalController {
    private final ApprovalService service;

    public record DecideReq(boolean approved, String comment) {}

    @GetMapping("/pending")
    public R<List<ApprovalInstance>> pending() {
        return R.ok(service.list(new LambdaQueryWrapper<ApprovalInstance>()
                .eq(ApprovalInstance::getStatus, ApprovalService.PENDING).orderByAsc(ApprovalInstance::getId)));
    }

    @PostMapping("/{id}/decide")
    @PreAuthorize("hasAuthority('workflow:approve')")
    public R<ApprovalInstance> decide(@PathVariable Long id, @RequestBody DecideReq req) {
        return R.ok(service.decide(id, req.approved(), req.comment()));
    }
}
