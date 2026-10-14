package com.akarin.erp.finance.controller;

import com.akarin.erp.common.web.R;
import com.akarin.erp.finance.dto.PaymentCreateReq;
import com.akarin.erp.finance.dto.SettleReq;
import com.akarin.erp.finance.entity.Payment;
import com.akarin.erp.finance.entity.Settlement;
import com.akarin.erp.finance.mapper.PaymentMapper;
import com.akarin.erp.finance.mapper.SettlementMapper;
import com.akarin.erp.finance.service.FinanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class SettlementController {
    private final FinanceService service;
    private final com.akarin.erp.common.idem.IdempotencyService idem;
    private final PaymentMapper paymentMapper;
    private final SettlementMapper settlementMapper;

    @PostMapping("/payments")
    @PreAuthorize("hasAuthority('finance:write')")
    public R<Payment> register(@Valid @RequestBody PaymentCreateReq req, @RequestHeader(value = com.akarin.erp.common.idem.IdempotencyService.HEADER, required = false) String idemKey) {
        return R.ok(idem.execute(idemKey, "finance.payment.create", req, () -> {
            String prefix = service.validatePayment(req);
            return com.akarin.erp.common.util.NoGenerator.reserveThen(java.util.Map.of(prefix, 1), () -> service.registerPayment(req));
        }, Payment::getId, service::payment));
    }

    @PreAuthorize("hasAuthority('finance:read')")
    @GetMapping("/payments")
    public R<Page<Payment>> payments(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                     @RequestParam(required = false) String paymentType,
                                     @RequestParam(required = false) Long partnerId) {
        return R.ok(paymentMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<Payment>()
                .eq(paymentType != null, Payment::getPaymentType, paymentType)
                .eq(partnerId != null, Payment::getPartnerId, partnerId).orderByDesc(Payment::getId)));
    }

    @PostMapping("/settlements")
    @PreAuthorize("hasAuthority('finance:write')")
    public R<Settlement> settle(@Valid @RequestBody SettleReq req) {
        try {
            return R.ok(service.settle(req));
        } catch (com.akarin.erp.common.exception.BizException e) {
            // 请求号撞唯一索引或乐观锁冲突：settle 的事务已回滚，按请求号重新查一次。
            // 已有成功记录：参数相同返回原结果，不同返回 40902；没有记录说明是别的请求号抢先，仍返回 40900。
            if (e.getErrorCode() != com.akarin.erp.common.exception.ErrorCode.CONCURRENT_MODIFICATION) throw e;
            return R.ok(service.replaySettlement(req, e));
        }
    }

    @PreAuthorize("hasAuthority('finance:read')")
    @GetMapping("/settlements")
    public R<Page<Settlement>> settlements(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                                           @RequestParam(required = false) Long billId) {
        return R.ok(settlementMapper.selectPage(new Page<>(page, size), new LambdaQueryWrapper<Settlement>()
                .eq(billId != null, Settlement::getBillId, billId).orderByDesc(Settlement::getId)));
    }
}
