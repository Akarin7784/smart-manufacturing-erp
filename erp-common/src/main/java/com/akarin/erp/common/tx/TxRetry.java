package com.akarin.erp.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 最外层整事务重试，只针对真实的锁冲突（死锁 / 锁等待超时）。
 * <p>分类规则：
 * <ol>
 *   <li>TransactionSystemException（回滚/提交本身失败）：先取 {@code getApplicationException()}，即导致回滚的原始异常；
 *       原始异常不是锁冲突（如 BizException）时，原样抛出原始异常，不重试，调用方拿到原来的业务码。</li>
 *   <li>沿 cause 链找真实锁冲突：PessimisticLockingFailureException（含 CannotAcquireLockException、
 *       DeadlockLoserDataAccessException），或 SQLException 错误码 MySQL 1205（锁等待超时）/1213（死锁）、
 *       H2 50200（锁超时）/40001（死锁），或 SQLState 40001。不再按异常消息里的 "rollback" 字样判断。</li>
 * </ol>
 * 锁冲突：每次重试打 WARN（op、第几次、异常类）；3 次仍失败抛 PessimisticLockingFailureException → 409/40900。
 * 非锁冲突的 UncategorizedSQLException（如 H2 "Transaction was illegally transitioned ..."，cause 链里没有锁错误码）：
 * 不重试、保持 500，并打 ERROR（op、异常类、SQLState/错误码；不打 SQL 和参数）。
 * 只能在事务外调用（如 controller）；已在事务中则不重试、直接执行，由外层决定。
 */
@Slf4j
@Component
public class TxRetry {
    static final int MAX_ATTEMPTS = 3;
    /** MySQL 1205 锁等待超时、1213 死锁；H2 50200 锁超时、40001 死锁 */
    static final Set<Integer> LOCK_ERROR_CODES = Set.of(1205, 1213, 50200, 40001);

    public <T> T run(String op, Supplier<T> txWork) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return txWork.get();
        for (int attempt = 1; ; attempt++) {
            try {
                return txWork.get();
            } catch (RuntimeException e) {
                Throwable original = original(e);
                if (!isLockConflict(original)) {
                    if (original != e && original instanceof RuntimeException r) throw r;   // 业务异常等：原码返回，不重试
                    if (e instanceof UncategorizedSQLException u) {
                        SQLException s = u.getSQLException();
                        log.error("tx failed (not a lock conflict, no retry): op={} exception={} sqlState={} errorCode={}",
                                op, e.getClass().getName(), s == null ? null : s.getSQLState(), s == null ? null : s.getErrorCode());
                    }
                    throw e;
                }
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

    /** 回滚/提交失败时取导致回滚的原始异常 */
    static Throwable original(Throwable e) {
        if (e instanceof TransactionSystemException t && t.getApplicationException() != null) return t.getApplicationException();
        return e;
    }

    static boolean isLockConflict(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PessimisticLockingFailureException) return true;
            if (t instanceof SQLException s && (LOCK_ERROR_CODES.contains(s.getErrorCode()) || "40001".equals(s.getSQLState()))) return true;
        }
        return false;
    }

    private static PessimisticLockingFailureException asLockFailure(RuntimeException e) {
        return e instanceof PessimisticLockingFailureException p ? p
                : new CannotAcquireLockException("锁冲突，重试后仍失败", e);
    }
}
