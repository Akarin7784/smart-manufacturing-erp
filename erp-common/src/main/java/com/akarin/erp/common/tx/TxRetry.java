package com.akarin.erp.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 最外层整事务重试，只针对锁冲突：
 * <ul>
 *   <li>PessimisticLockingFailureException（DeadlockLoserDataAccessException、CannotAcquireLockException 等，死锁 / 锁等待超时）；</li>
 *   <li>锁冲突之后回滚本身失败的 TransactionSystemException（H2 锁超时后事务已被数据库关闭，再 rollback 报
 *       "illegally transitioned from CLOSED to ROLLING_BACK"）。事务没有提交，重来是安全的。提交失败不重试（可能已提交）。</li>
 * </ul>
 * 每次重试打 WARN（操作名、第几次、异常类）。3 次仍失败时抛 PessimisticLockingFailureException，
 * 由 GlobalExceptionHandler 统一映射为 HTTP 409 / 40900，绝不以 500 返回。
 * 只能在事务外调用（如 controller）；已在事务中则不重试、直接执行，由外层决定。
 */
@Slf4j
@Component
public class TxRetry {
    static final int MAX_ATTEMPTS = 3;

    public <T> T run(String op, Supplier<T> txWork) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return txWork.get();
        for (int attempt = 1; ; attempt++) {
            try {
                return txWork.get();
            } catch (RuntimeException e) {
                if (!isLockConflict(e)) throw e;
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("tx retry exhausted: op={} attempts={} exception={}", op, attempt, e.getClass().getName());
                    throw asLockFailure(e);
                }
                log.warn("tx retry: op={} attempt={}/{} exception={}", op, attempt, MAX_ATTEMPTS, e.getClass().getName());
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 30L * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw asLockFailure(e);
                }
            }
        }
    }

    static boolean isLockConflict(RuntimeException e) {
        if (e instanceof PessimisticLockingFailureException) return true;
        return e instanceof TransactionSystemException t && t.getMessage() != null
                && t.getMessage().contains("rollback");
    }

    private static PessimisticLockingFailureException asLockFailure(RuntimeException e) {
        return e instanceof PessimisticLockingFailureException p ? p
                : new CannotAcquireLockException("锁冲突后事务回滚失败（事务未提交）", e);
    }
}
