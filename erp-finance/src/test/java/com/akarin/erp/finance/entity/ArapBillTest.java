package com.akarin.erp.finance.entity;

import com.akarin.erp.common.exception.BizException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class ArapBillTest {
    static ArapBill bill(String amount) {
        ArapBill b = new ArapBill();
        b.setBillNo("AP1"); b.setAmount(new BigDecimal(amount)); b.setSettledAmount(BigDecimal.ZERO); b.setStatus(ArapBill.OPEN);
        return b;
    }

    @Test void openToPartialToSettled() {
        ArapBill b = bill("100.00");
        b.settle(new BigDecimal("30"));
        assertThat(b.getStatus()).isEqualTo(ArapBill.PARTIAL);
        assertThat(b.remaining()).isEqualByComparingTo("70");
        b.settle(new BigDecimal("70.00"));
        assertThat(b.getStatus()).isEqualTo(ArapBill.SETTLED);
        assertThat(b.remaining()).isEqualByComparingTo("0");
    }

    @Test void fullSettleInOneGo() {
        ArapBill b = bill("50");
        b.settle(new BigDecimal("50"));
        assertThat(b.getStatus()).isEqualTo(ArapBill.SETTLED);
    }

    @Test void rejectOverSettlement() {
        ArapBill b = bill("100");
        b.settle(new BigDecimal("60"));
        assertThatThrownBy(() -> b.settle(new BigDecimal("40.01"))).isInstanceOf(BizException.class).hasMessageContaining("超额");
        assertThat(b.getSettledAmount()).isEqualByComparingTo("60");
        assertThat(b.getStatus()).isEqualTo(ArapBill.PARTIAL);
    }

    @Test void rejectSettleAfterSettled() {
        ArapBill b = bill("10");
        b.settle(BigDecimal.TEN);
        assertThatThrownBy(() -> b.settle(BigDecimal.ONE)).isInstanceOf(BizException.class).hasMessageContaining("已核销");
    }

    @Test void rejectNonPositive() {
        ArapBill b = bill("10");
        assertThatThrownBy(() -> b.settle(BigDecimal.ZERO)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> b.settle(new BigDecimal("-1"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> b.settle(null)).isInstanceOf(BizException.class);
        assertThat(b.getStatus()).isEqualTo(ArapBill.OPEN);
    }
}
