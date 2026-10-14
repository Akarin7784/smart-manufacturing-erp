package com.akarin.erp.common.tx;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 最外层整事务重试：仅用于死锁 / 锁等待超时（PessimisticLockingFailureException，含 DeadlockLoserDataAccessException、
 * CannotAcquireLockException）。数据库已把失败事务整体回滚，重新执行整个 @Transactional 方法是安全的。
 * 只能在事务外调用（如 controller）；若已在事务中则不重试、直接执行，由外层决定，避免在半回滚的事务里重来。
 */
@Component
public class TxRetry {
    static final int MAX_ATTEMPTS = 3;

    public <T> T run(Supplier<T> txWork) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return txWork.get();
        for (int attempt = 1; ; attempt++) {
            try {
                return txWork.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) throw e;   // 交给 GlobalExceptionHandler → 40900
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 30L * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
