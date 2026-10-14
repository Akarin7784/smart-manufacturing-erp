package com.akarin.erp.finance.service;

import com.akarin.erp.common.event.StockBizCompletedEvent;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.util.Money;
import com.akarin.erp.common.util.NoGenerator;
import com.akarin.erp.finance.dto.PaymentCreateReq;
import com.akarin.erp.finance.dto.ReconciliationRow;
import com.akarin.erp.finance.dto.SettleReq;
import com.akarin.erp.finance.entity.ArapBill;
import com.akarin.erp.finance.entity.Payment;
import com.akarin.erp.finance.entity.Settlement;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.akarin.erp.finance.mapper.PaymentMapper;
import com.akarin.erp.finance.mapper.SettlementMapper;
import com.akarin.erp.mdm.service.CustomerService;
import com.akarin.erp.mdm.service.SupplierService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FinanceService {
    static final int PAYMENT_TERM_DAYS = 30;
    private final ArapBillMapper billMapper;
    private final PaymentMapper paymentMapper;
    private final SettlementMapper settlementMapper;
    private final CustomerService customerService;
    private final SupplierService supplierService;

    /** 采购入库 → 应付；销售出库 → 应收。同步监听，与出入库同一事务；按来源单号幂等 */
    @EventListener
    @Transactional
    public ArapBill onStockBizCompleted(StockBizCompletedEvent e) {
        String billType = switch (e.bizType()) {
            case StockBizCompletedEvent.PURCHASE_RECEIPT -> ArapBill.AP;
            case StockBizCompletedEvent.SALES_SHIPMENT -> ArapBill.AR;
            default -> null;
        };
        if (billType == null) return null;
        ArapBill existing = billMapper.selectOne(new LambdaQueryWrapper<ArapBill>()
                .eq(ArapBill::getSourceBizType, e.bizType()).eq(ArapBill::getSourceBizNo, e.bizNo()));
        if (existing != null) return existing;
        ArapBill b = new ArapBill();
        b.setBillNo(NoGenerator.next(billType));
        b.setBillType(billType);
        b.setPartnerId(e.partnerId());
        b.setSourceBizType(e.bizType());
        b.setSourceBizNo(e.bizNo());
        b.setAmount(Money.of(e.amount()));
        b.setSettledAmount(Money.of(BigDecimal.ZERO));
        b.setStatus(ArapBill.OPEN);
        b.setDueDate(LocalDate.now().plusDays(PAYMENT_TERM_DAYS));
        b.setVersion(0);
        billMapper.insert(b);
        return b;
    }

    /** 收付款登记的输入校验，不开事务、不取号（#22）；返回需要的单号前缀 */
    public String validatePayment(PaymentCreateReq req) {
        String billType = Payment.billTypeOf(req.paymentType());
        if (ArapBill.AR.equals(billType)) customerService.requireRef(req.partnerId());
        else supplierService.requireRef(req.partnerId());
        Money.requireAmount(req.amount(), "收付款金额");
        return Payment.RECEIPT.equals(req.paymentType()) ? "RC" : "PY";
    }

    public Payment payment(Long id) { return paymentMapper.selectById(id); }

    @Transactional
    public Payment registerPayment(PaymentCreateReq req) {
        String billType = Payment.billTypeOf(req.paymentType());
        if (ArapBill.AR.equals(billType)) customerService.requireRef(req.partnerId());
        else supplierService.requireRef(req.partnerId());
        BigDecimal amount = Money.requireAmount(req.amount(), "收付款金额");
        Payment p = new Payment();
        p.setPaymentNo(NoGenerator.next(Payment.RECEIPT.equals(req.paymentType()) ? "RC" : "PY"));
        p.setPaymentType(req.paymentType());
        p.setPartnerId(req.partnerId());
        p.setAmount(amount);
        p.setAppliedAmount(Money.of(BigDecimal.ZERO));
        p.setPaidDate(req.paidDate() == null ? LocalDate.now() : req.paidDate());
        p.setRemark(req.remark());
        p.setVersion(0);
        paymentMapper.insert(p);
        return p;
    }

    /**
     * 核销。幂等：同一 requestNo 且参数一致直接返回原结果，参数不一致返回 409/40902。
     * 并发：单据与收付款均带乐观锁，版本冲突整笔回滚并返回 409/40900，由调用方重试。
     */
    @Transactional
    public Settlement settle(SettleReq req) {
        BigDecimal amt = Money.requireAmount(req.amount(), "核销金额");
        Settlement done = settlementMapper.selectOne(
                new LambdaQueryWrapper<Settlement>().eq(Settlement::getRequestNo, req.requestNo()));
        if (done != null) return idempotent(done, req);

        ArapBill bill = billMapper.selectById(req.billId());
        if (bill == null) throw new BizException(ErrorCode.BAD_REQUEST, "应收应付单不存在: " + req.billId());
        Payment pay = paymentMapper.selectById(req.paymentId());
        if (pay == null) throw new BizException(ErrorCode.BAD_REQUEST, "收付款不存在: " + req.paymentId());
        if (!Payment.billTypeOf(pay.getPaymentType()).equals(bill.getBillType()))
            throw new BizException("收付款类型与单据类型不匹配");
        if (!pay.getPartnerId().equals(bill.getPartnerId()))
            throw new BizException("收付款与单据的往来单位不一致");

        bill.settle(amt);
        pay.apply(amt);
        if (billMapper.updateById(bill) != 1 || paymentMapper.updateById(pay) != 1)
            throw new BizException(ErrorCode.CONCURRENT_MODIFICATION);

        Settlement s = new Settlement();
        s.setRequestNo(req.requestNo());
        s.setPaymentId(pay.getId());
        s.setBillId(bill.getId());
        s.setAmount(amt);
        try {
            settlementMapper.insert(s);
        } catch (DuplicateKeyException ex) {
            throw new DuplicateRequestNoException(req.requestNo());
        }
        return s;
    }

    /** 并发冲突（请求号撞唯一索引或乐观锁）的失败方：在新事务里读取已提交的那条，参数相同返回原结果，不同返回 409/40902 */
    @Transactional(readOnly = true)
    public Settlement replaySettlement(SettleReq req, BizException original) {
        Settlement done = settlementMapper.selectOne(
                new LambdaQueryWrapper<Settlement>().eq(Settlement::getRequestNo, req.requestNo()));
        if (done == null) throw original;
        return idempotent(done, req);
    }

    private Settlement idempotent(Settlement done, SettleReq req) {
        boolean same = done.getBillId().equals(req.billId()) && done.getPaymentId().equals(req.paymentId())
                && Money.of(done.getAmount()).compareTo(Money.of(req.amount())) == 0;
        if (!same) throw new BizException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "核销请求号已被使用且参数不一致: " + req.requestNo());
        return done;
    }

    public ArapBill requireBill(Long id) {
        ArapBill b = billMapper.selectById(id);
        if (b == null) throw new BizException(ErrorCode.NOT_FOUND, "应收应付单不存在: " + id);
        return b;
    }

    public List<ReconciliationRow> reconcile(String billType, Long partnerId) {
        return billMapper.reconcile(billType, partnerId);
    }
}
