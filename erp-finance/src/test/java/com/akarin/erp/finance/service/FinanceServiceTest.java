package com.akarin.erp.finance.service;

import com.akarin.erp.common.event.StockBizCompletedEvent;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.finance.dto.PaymentCreateReq;
import com.akarin.erp.finance.dto.SettleReq;
import com.akarin.erp.finance.entity.ArapBill;
import com.akarin.erp.finance.entity.Payment;
import com.akarin.erp.finance.entity.Settlement;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.akarin.erp.finance.mapper.PaymentMapper;
import com.akarin.erp.finance.mapper.SettlementMapper;
import com.akarin.erp.mdm.service.CustomerService;
import com.akarin.erp.mdm.service.SupplierService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FinanceServiceTest {
    static final Class<?>[] ENTITIES = {ArapBill.class, Payment.class, Settlement.class};
    @BeforeAll
    static void initMybatisLambdaCache() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : ENTITIES) TableInfoHelper.initTableInfo(assistant, c);
    }

    ArapBillMapper billMapper = mock(ArapBillMapper.class);
    PaymentMapper paymentMapper = mock(PaymentMapper.class);
    SettlementMapper settlementMapper = mock(SettlementMapper.class);
    CustomerService customerService = mock(CustomerService.class);
    SupplierService supplierService = mock(SupplierService.class);
    FinanceService svc;

    @BeforeEach void setUp() {
        svc = new FinanceService(billMapper, paymentMapper, settlementMapper, customerService, supplierService);
        when(billMapper.updateById(any(ArapBill.class))).thenReturn(1);
        when(paymentMapper.updateById(any(Payment.class))).thenReturn(1);
    }

    static ArapBill bill(String type, long partner, String amount) {
        ArapBill b = new ArapBill();
        b.setId(10L); b.setBillNo("B"); b.setBillType(type); b.setPartnerId(partner);
        b.setAmount(new BigDecimal(amount)); b.setSettledAmount(BigDecimal.ZERO); b.setStatus(ArapBill.OPEN);
        return b;
    }
    static Payment pay(String type, long partner, String amount) {
        Payment p = new Payment();
        p.setId(20L); p.setPaymentType(type); p.setPartnerId(partner);
        p.setAmount(new BigDecimal(amount)); p.setAppliedAmount(BigDecimal.ZERO);
        return p;
    }

    @Test void purchaseReceiptCreatesPayable() {
        ArapBill b = svc.onStockBizCompleted(new StockBizCompletedEvent(StockBizCompletedEvent.PURCHASE_RECEIPT, "PO1", 3L, new BigDecimal("50.005")));
        assertThat(b.getBillType()).isEqualTo(ArapBill.AP);
        assertThat(b.getAmount()).isEqualTo(new BigDecimal("50.01"));
        assertThat(b.getStatus()).isEqualTo(ArapBill.OPEN);
        verify(billMapper).insert(any(ArapBill.class));
    }

    @Test void salesShipmentCreatesReceivable() {
        ArapBill b = svc.onStockBizCompleted(new StockBizCompletedEvent(StockBizCompletedEvent.SALES_SHIPMENT, "SO1", 4L, BigDecimal.TEN));
        assertThat(b.getBillType()).isEqualTo(ArapBill.AR);
        assertThat(b.getPartnerId()).isEqualTo(4L);
    }

    @Test void billCreationIsIdempotentPerSource() {
        ArapBill existing = bill(ArapBill.AP, 3, "1");
        when(billMapper.selectOne(any())).thenReturn(existing);
        assertThat(svc.onStockBizCompleted(new StockBizCompletedEvent(StockBizCompletedEvent.PURCHASE_RECEIPT, "PO1", 3L, BigDecimal.ONE))).isSameAs(existing);
        verify(billMapper, never()).insert(any(ArapBill.class));
    }

    @Test void unknownEventIgnored() {
        assertThat(svc.onStockBizCompleted(new StockBizCompletedEvent("MANUAL", "X", 1L, BigDecimal.ONE))).isNull();
        verifyNoInteractions(billMapper);
    }

    @Test void registerPaymentValidatesPartner() {
        Payment p = svc.registerPayment(new PaymentCreateReq(Payment.PAYMENT, 3L, new BigDecimal("20"), null, "r"));
        verify(supplierService).require(3L);
        assertThat(p.getPaymentNo()).startsWith("PY");
        assertThat(p.getAppliedAmount()).isEqualByComparingTo("0");
        Payment r = svc.registerPayment(new PaymentCreateReq(Payment.RECEIPT, 4L, new BigDecimal("20"), null, null));
        verify(customerService).require(4L);
        assertThat(r.getPaymentNo()).startsWith("RC");
    }

    @Test void partialThenFullSettlement() {
        ArapBill b = bill(ArapBill.AP, 3, "100");
        Payment p = pay(Payment.PAYMENT, 3, "100");
        when(billMapper.selectById(10L)).thenReturn(b);
        when(paymentMapper.selectById(20L)).thenReturn(p);
        svc.settle(new SettleReq("R1", 20L, 10L, new BigDecimal("40")));
        assertThat(b.getStatus()).isEqualTo(ArapBill.PARTIAL);
        svc.settle(new SettleReq("R2", 20L, 10L, new BigDecimal("60")));
        assertThat(b.getStatus()).isEqualTo(ArapBill.SETTLED);
        assertThat(p.unapplied()).isEqualByComparingTo("0");
        ArgumentCaptor<Settlement> cap = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper, times(2)).insert(cap.capture());
        assertThat(cap.getAllValues()).extracting(Settlement::getRequestNo).containsExactly("R1", "R2");
    }

    @Test void overSettlementRejected() {
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AP, 3, "100"));
        when(paymentMapper.selectById(20L)).thenReturn(pay(Payment.PAYMENT, 3, "500"));
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, new BigDecimal("100.01"))))
                .isInstanceOf(BizException.class).hasMessageContaining("超额");
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test void overPaymentBalanceRejected() {
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AP, 3, "100"));
        when(paymentMapper.selectById(20L)).thenReturn(pay(Payment.PAYMENT, 3, "30"));
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, new BigDecimal("31"))))
                .isInstanceOf(BizException.class).hasMessageContaining("超额");
    }

    @Test void duplicateRequestIsIdempotent() {
        Settlement done = new Settlement();
        done.setRequestNo("R1"); done.setBillId(10L); done.setPaymentId(20L); done.setAmount(new BigDecimal("40.00"));
        when(settlementMapper.selectOne(any())).thenReturn(done);
        assertThat(svc.settle(new SettleReq("R1", 20L, 10L, new BigDecimal("40")))).isSameAs(done);
        verify(billMapper, never()).updateById(any(ArapBill.class));
    }

    @Test void reusedRequestNoWithDifferentParamsConflicts() {
        Settlement done = new Settlement();
        done.setRequestNo("R1"); done.setBillId(10L); done.setPaymentId(20L); done.setAmount(new BigDecimal("40"));
        when(settlementMapper.selectOne(any())).thenReturn(done);
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, new BigDecimal("41"))))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(409);
    }

    @Test void concurrentDuplicateInsertConflicts() {
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AP, 3, "100"));
        when(paymentMapper.selectById(20L)).thenReturn(pay(Payment.PAYMENT, 3, "100"));
        when(settlementMapper.insert(any(Settlement.class))).thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, BigDecimal.ONE)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(409);
    }

    @Test void optimisticLockConflict() {
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AP, 3, "100"));
        when(paymentMapper.selectById(20L)).thenReturn(pay(Payment.PAYMENT, 3, "100"));
        when(billMapper.updateById(any(ArapBill.class))).thenReturn(0);
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, BigDecimal.ONE)))
                .isInstanceOf(BizException.class).extracting("code").isEqualTo(409);
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test void typeAndPartnerMismatchRejected() {
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AR, 3, "100"));
        when(paymentMapper.selectById(20L)).thenReturn(pay(Payment.PAYMENT, 3, "100"));
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, BigDecimal.ONE))).hasMessageContaining("类型");
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AP, 9, "100"));
        assertThatThrownBy(() -> svc.settle(new SettleReq("R2", 20L, 10L, BigDecimal.ONE))).hasMessageContaining("往来单位");
    }

    @Test void sub_centAmountsRejectedBeforeAnyDbWork() {
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, new BigDecimal("0.004")))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, null))).isInstanceOf(BizException.class);
        verifyNoInteractions(settlementMapper);
        assertThatThrownBy(() -> svc.registerPayment(new PaymentCreateReq(Payment.PAYMENT, 3L, new BigDecimal("0.001"), null, null)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> svc.registerPayment(new PaymentCreateReq(Payment.PAYMENT, 3L, null, null, null)))
                .isInstanceOf(BizException.class);
        verify(paymentMapper, never()).insert(any(Payment.class));
    }

    @Test void settleAmountIsRoundedHalfUp() {
        ArapBill b = bill(ArapBill.AP, 3, "1");
        when(billMapper.selectById(10L)).thenReturn(b);
        when(paymentMapper.selectById(20L)).thenReturn(pay(Payment.PAYMENT, 3, "1"));
        Settlement st = svc.settle(new SettleReq("R1", 20L, 10L, new BigDecimal("0.125")));
        assertThat(st.getAmount()).isEqualTo(new BigDecimal("0.13"));
        assertThat(b.getSettledAmount()).isEqualByComparingTo("0.13");
    }

    @Test void paymentAndBillUpdateConflicts() {
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AP, 3, "100"));
        when(paymentMapper.selectById(20L)).thenReturn(pay(Payment.PAYMENT, 3, "100"));
        when(paymentMapper.updateById(any(Payment.class))).thenReturn(0);
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, BigDecimal.ONE))).extracting("code").isEqualTo(409);
    }

    @Test void idempotencyComparesBillAndPayment() {
        Settlement done = new Settlement();
        done.setRequestNo("R1"); done.setBillId(10L); done.setPaymentId(20L); done.setAmount(BigDecimal.ONE);
        when(settlementMapper.selectOne(any())).thenReturn(done);
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 11L, BigDecimal.ONE))).extracting("code").isEqualTo(409);
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 21L, 10L, BigDecimal.ONE))).extracting("code").isEqualTo(409);
    }

    @Test void registerPaymentKeepsGivenDate() {
        var d = java.time.LocalDate.of(2026, 1, 2);
        assertThat(svc.registerPayment(new PaymentCreateReq(Payment.RECEIPT, 4L, BigDecimal.ONE, d, null)).getPaidDate()).isEqualTo(d);
    }

    @Test void requireBill() {
        assertThatThrownBy(() -> svc.requireBill(1L)).extracting("code").isEqualTo(404);
        ArapBill b = bill(ArapBill.AR, 1, "1");
        when(billMapper.selectById(10L)).thenReturn(b);
        assertThat(svc.requireBill(10L)).isSameAs(b);
    }

    @Test void missingBillOrPayment() {
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, BigDecimal.ONE))).extracting("code").isEqualTo(404);
        when(billMapper.selectById(10L)).thenReturn(bill(ArapBill.AP, 3, "100"));
        assertThatThrownBy(() -> svc.settle(new SettleReq("R1", 20L, 10L, BigDecimal.ONE))).extracting("code").isEqualTo(404);
    }
}
