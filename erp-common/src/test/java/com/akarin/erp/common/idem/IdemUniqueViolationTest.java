package com.akarin.erp.common.idem;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/** 只有撞本单据表的 uk_*_idem 才按“对方已提交”重放；其他唯一冲突与错误照常抛出 */
class IdemUniqueViolationTest {
    @Test void mysqlMessage() {
        var e = new DuplicateKeyException("x", new SQLIntegrityConstraintViolationException("Duplicate entry 'admin-k' for key 'pur_order.uk_pur_idem'", "23000", 1062));
        assertThat(IdempotencyService.isIdemUniqueViolation(e, "uk_pur_idem")).isTrue();
        assertThat(IdempotencyService.isIdemUniqueViolation(e, "uk_sal_idem")).isFalse();
    }

    @Test void h2Message() {
        var e = new DuplicateKeyException("x", new SQLException("Unique index or primary key violation: \"PUBLIC.UK_TXN_IDEM_INDEX_8 ON PUBLIC.INV_STOCK_TXN(IDEM_USER NULLS FIRST, IDEM_KEY NULLS FIRST)\"", "23505", 23505));
        assertThat(IdempotencyService.isIdemUniqueViolation(e, "uk_txn_idem")).isTrue();
    }

    @Test void otherUniqueOrOtherErrorsAreNotReplayed() {
        var orderNo = new DuplicateKeyException("x", new SQLIntegrityConstraintViolationException("Duplicate entry 'PO1' for key 'pur_order.order_no'", "23000", 1062));
        assertThat(IdempotencyService.isIdemUniqueViolation(orderNo, "uk_pur_idem")).isFalse();
        assertThat(IdempotencyService.isIdemUniqueViolation(new RuntimeException("uk_pur_idem mentioned but not a constraint error"), "uk_pur_idem")).isFalse();
        assertThat(IdempotencyService.isIdemUniqueViolation(new IllegalStateException(), "uk_pur_idem")).isFalse();
    }
}
