package com.akarin.erp.finance.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.finance.dto.ReconciliationRow;
import com.akarin.erp.finance.entity.ArapBill;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.akarin.erp.finance.service.FinanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class ArapBillController {
    private final ArapBillMapper mapper;
    private final FinanceService financeService;

    @GetMapping("/bills")
    public R<Page<ArapBill>> page(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                  @RequestParam(required = false) String billType,
                                  @RequestParam(required = false) Long partnerId,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String sourceBizNo) {
        return R.ok(mapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<ArapBill>()
                .eq(billType != null, ArapBill::getBillType, billType)
                .eq(partnerId != null, ArapBill::getPartnerId, partnerId)
                .eq(status != null, ArapBill::getStatus, status)
                .eq(sourceBizNo != null, ArapBill::getSourceBizNo, sourceBizNo)
                .orderByDesc(ArapBill::getId)));
    }

    @GetMapping("/bills/{id}")
    public R<ArapBill> get(@PathVariable Long id) { return R.ok(mapper.selectById(id)); }

    /** 对账：按客户（AR）/供应商（AP）汇总 */
    @GetMapping("/reconciliation")
    public R<List<ReconciliationRow>> reconcile(@RequestParam(required = false) String billType,
                                                @RequestParam(required = false) Long partnerId) {
        return R.ok(financeService.reconcile(billType, partnerId));
    }
}
