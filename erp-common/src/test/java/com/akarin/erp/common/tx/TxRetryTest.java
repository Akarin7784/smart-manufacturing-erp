package com.akarin.erp.common.tx;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class TxRetryTest {
    TxRetry retry = new TxRetry();

    @Test void retriesDeadlockThenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        String r = retry.run("t", () -> {
            if (n.incrementAndGet() < 3) throw new DeadlockLoserDataAccessException("dl", null);
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(n).hasValue(3);
    }

    @Test void givesUpAfterMaxAttempts() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw new CannotAcquireLockException("lock"); }))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(n).hasValue(TxRetry.MAX_ATTEMPTS);
    }

    @Test void otherExceptionsAreNotRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw new DuplicateKeyException("dup"); }))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(n).hasValue(1);
    }

    @Test void insideActiveTransactionNoRetry() {
        AtomicInteger n = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw new DeadlockLoserDataAccessException("dl", null); }))
                    .isInstanceOf(DeadlockLoserDataAccessException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(n).hasValue(1);
    }

    @Test void interruptedDuringBackoffStops() {
        Thread.currentThread().interrupt();
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw new DeadlockLoserDataAccessException("dl", null); }))
                .isInstanceOf(DeadlockLoserDataAccessException.class);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(n).hasValue(1);
    }

    /** H2：锁超时后 rollback 失败（CLOSED -> ROLLING_BACK），事务未提交 → 也重试 */
    @Test void rollbackFailureIsRetried() {
        AtomicInteger n = new AtomicInteger();
        String r = retry.run("t", () -> {
            if (n.incrementAndGet() == 1) throw new TransactionSystemException("JDBC rollback failed");
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(n).hasValue(2);
    }

    /** 回滚失败重试耗尽：转成 PessimisticLockingFailureException（→ 409/40900），不能落到 500 */
    @Test void rollbackFailureExhaustedBecomesLockFailure() {
        assertThatThrownBy(() -> retry.run("t", () -> { throw new TransactionSystemException("JDBC rollback failed"); }))
                .isInstanceOf(PessimisticLockingFailureException.class)
                .hasCauseInstanceOf(TransactionSystemException.class);
    }

    /** 提交失败可能已经提交，不重试 */
    @Test void commitFailureNotRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw new TransactionSystemException("Could not commit JDBC transaction"); }))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(n).hasValue(1);
    }
}
