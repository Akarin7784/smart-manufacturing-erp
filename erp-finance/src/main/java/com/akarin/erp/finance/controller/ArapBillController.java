package com.akarin.erp.finance.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.finance.entity.ArapBill;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 应收应付查询（骨架；生成逻辑待接入采购收货/销售发货事件） */
@RestController
@RequestMapping("/api/finance/bills")
@RequiredArgsConstructor
public class ArapBillController {
    private final ArapBillMapper mapper;

    @GetMapping
    public R<Page<ArapBill>> page(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                  @RequestParam(required = false) String billType) {
        return R.ok(mapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<ArapBill>()
                .eq(billType != null, ArapBill::getBillType, billType).orderByDesc(ArapBill::getId)));
    }
}
