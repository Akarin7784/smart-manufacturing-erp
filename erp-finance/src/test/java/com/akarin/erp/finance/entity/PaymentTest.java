package com.akarin.erp.finance.entity;

import com.akarin.erp.common.exception.BizException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class PaymentTest {
    @Test void applyUntilExhausted() {
        Payment p = new Payment();
        p.setAmount(new BigDecimal("100")); p.setAppliedAmount(BigDecimal.ZERO);
        p.apply(new BigDecimal("40"));
        assertThat(p.unapplied()).isEqualByComparingTo("60");
        assertThatThrownBy(() -> p.apply(new BigDecimal("60.01"))).isInstanceOf(BizException.class);
        p.apply(new BigDecimal("60"));
        assertThat(p.unapplied()).isEqualByComparingTo("0");
    }

    @Test void applyRejectsAmountRoundingToZero() {
        Payment p = new Payment();
        p.setAmount(BigDecimal.TEN); p.setAppliedAmount(BigDecimal.ZERO);
        assertThatThrownBy(() -> p.apply(new BigDecimal("0.001"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> p.apply(null)).isInstanceOf(BizException.class);
        assertThat(p.unapplied()).isEqualByComparingTo("10");
    }

    @Test void billTypeMapping() {
        assertThat(Payment.billTypeOf(Payment.RECEIPT)).isEqualTo(ArapBill.AR);
        assertThat(Payment.billTypeOf(Payment.PAYMENT)).isEqualTo(ArapBill.AP);
        assertThatThrownBy(() -> Payment.billTypeOf("X")).isInstanceOf(BizException.class);
    }
}
