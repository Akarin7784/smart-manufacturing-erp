package com.akarin.erp.common.tx;

import com.akarin.erp.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;

import java.sql.SQLException;
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

    static TransactionSystemException rollbackFailed(Throwable original) {
        TransactionSystemException t = new TransactionSystemException("JDBC rollback failed");
        if (original != null) t.initApplicationException(original);
        return t;
    }

    static UncategorizedSQLException uncategorized(SQLException cause) {
        return new UncategorizedSQLException("op", null, cause);
    }

    /** 业务异常 + 回滚失败：原样抛业务异常（原业务码），不重试 */
    @Test void businessExceptionThenRollbackFailureRethrowsOriginalNoRetry() {
        AtomicInteger n = new AtomicInteger();
        BizException biz = new BizException("库存不足");
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw rollbackFailed(biz); }))
                .isSameAs(biz);
        assertThat(n).hasValue(1);
    }

    @Test void nonLockOriginalBehindRollbackFailureRethrownNoRetry() {
        AtomicInteger n = new AtomicInteger();
        IllegalStateException ise = new IllegalStateException("x");
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw rollbackFailed(ise); })).isSameAs(ise);
        assertThat(n).hasValue(1);
    }

    @Test void deadlockBehindRollbackFailureIsRetried() {
        AtomicInteger n = new AtomicInteger();
        String r = retry.run("t", () -> {
            if (n.incrementAndGet() == 1) throw rollbackFailed(new DeadlockLoserDataAccessException("dl", null));
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(n).hasValue(2);
    }

    /** 不再按消息里的 "rollback" 判断：没有原始异常、cause 链也没有锁错误码 → 不重试 */
    @Test void bareRollbackFailureIsNotRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw rollbackFailed(null); }))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(n).hasValue(1);
    }

    @Test void lockErrorCodesInCauseChainAreRetried() {
        for (SQLException s : new SQLException[]{
                new SQLException("lock wait timeout", "HY000", 1205), new SQLException("deadlock", "40001", 1213),
                new SQLException("h2 lock timeout", "HYT00", 50200), new SQLException("h2 deadlock", "40001", 40001),
                new SQLException("other vendor", "40001", 7)}) {
            AtomicInteger n = new AtomicInteger();
            assertThat(retry.run("t", () -> {
                if (n.incrementAndGet() == 1) throw uncategorized(s);
                return "ok";
            })).isEqualTo("ok");
            assertThat(n).as(s.getMessage()).hasValue(2);
        }
    }

    @Test void lockUncategorizedExhaustedBecomesLockFailure() {
        assertThatThrownBy(() -> retry.run("t", () -> { throw uncategorized(new SQLException("deadlock", "40001", 1213)); }))
                .isInstanceOf(PessimisticLockingFailureException.class).hasCauseInstanceOf(UncategorizedSQLException.class);
    }

    /** cause 链里没有锁错误码的 UncategorizedSQLException：不重试，原样抛出（→ 500） */
    @Test void nonLockUncategorizedNotRetried() {
        AtomicInteger n = new AtomicInteger();
        UncategorizedSQLException u = uncategorized(new SQLException("General error: illegally transitioned", "HY000", 50000));
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw u; })).isSameAs(u);
        assertThat(n).hasValue(1);
    }

    @Test void selfCausedChainTerminates() {
        assertThat(TxRetry.isLockConflict(new RuntimeException("x"))).isFalse();
        assertThat(TxRetry.isLockConflict(null)).isFalse();
    }

    /** 提交失败可能已经提交，不重试 */
    @Test void commitFailureNotRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run("t", () -> { n.incrementAndGet(); throw new TransactionSystemException("Could not commit JDBC transaction"); }))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(n).hasValue(1);
    }
}
