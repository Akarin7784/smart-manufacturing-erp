package com.akarin.erp.common.tx;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class TxRetryTest {
    TxRetry retry = new TxRetry();

    @Test void retriesDeadlockThenSucceeds() {
        AtomicInteger n = new AtomicInteger();
        String r = retry.run(() -> {
            if (n.incrementAndGet() < 3) throw new DeadlockLoserDataAccessException("dl", null);
            return "ok";
        });
        assertThat(r).isEqualTo("ok");
        assertThat(n).hasValue(3);
    }

    @Test void givesUpAfterMaxAttempts() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> { n.incrementAndGet(); throw new CannotAcquireLockException("lock"); }))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(n).hasValue(TxRetry.MAX_ATTEMPTS);
    }

    @Test void otherExceptionsAreNotRetried() {
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> { n.incrementAndGet(); throw new DuplicateKeyException("dup"); }))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(n).hasValue(1);
    }

    @Test void insideActiveTransactionNoRetry() {
        AtomicInteger n = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> retry.run(() -> { n.incrementAndGet(); throw new DeadlockLoserDataAccessException("dl", null); }))
                    .isInstanceOf(DeadlockLoserDataAccessException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(n).hasValue(1);
    }

    @Test void interruptedDuringBackoffStops() {
        Thread.currentThread().interrupt();
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> { n.incrementAndGet(); throw new DeadlockLoserDataAccessException("dl", null); }))
                .isInstanceOf(DeadlockLoserDataAccessException.class);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(n).hasValue(1);
    }
}
