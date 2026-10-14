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
    /**
     * 锁冲突错误码（SQLException.getErrorCode()）：
     * MySQL 1205 锁等待超时、1213 死锁；H2 50200 锁超时（LOCK_TIMEOUT_1）、40001 死锁（DEADLOCK_1）。
     * 注意 40001 同时也是 MySQL 1213 的 SQLState（下面另按 SQLState 40001 判断）。两处重叠是有意的：
     * H2 的死锁错误码恰好等于标准 SQLState，按错误码和按 SQLState 都能识别，任一命中即可。
     */
    static final Set<Integer> LOCK_ERROR_CODES = Set.of(1205, 1213, 50200, 40001);
    static final Set<Integer> TIMEOUT_CODES = Set.of(1205, 50200);

    public <T> T run(String op, Supplier<T> txWork) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return txWork.get();
        for (int attempt = 1; ; attempt++) {
            try {
                return txWork.get();
            } catch (RuntimeException e) {
                Throwable original = original(e);
                if (!isLockConflict(original)) {
                    if (original != e && original instanceof RuntimeException r) throw r;   // 业务异常等：原码返回，不重试
                    if (e instanceof UncategorizedSQLException || e instanceof TransactionSystemException) {
                        // 简短 ERROR：op、异常类、SQLState、错误码；不带 SQL（异常消息里有）也不带堆栈 → 500
                        SQLException s = sqlCause(e);
                        log.error("tx failed (not a lock conflict, no retry): op={} exception={} sqlState={} errorCode={}",
                                op, e.getClass().getName(), s == null ? null : s.getSQLState(), s == null ? null : s.getErrorCode());
                    }
                    throw e;
                }
                Throwable lock = lockCause(original);
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("tx retry exhausted: op={} attempts={} kind={} exception={}{}", op, attempt, kind(lock), lock.getClass().getName(), codes(lock));
                    throw asLockFailure(e);
                }
                log.warn("tx retry: op={} attempt={}/{} kind={} exception={}{}", op, attempt, MAX_ATTEMPTS, kind(lock), lock.getClass().getName(), codes(lock));
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

    static boolean isLockConflict(Throwable e) { return lockCause(e) != null; }

    /** cause 链上第一个真正的锁冲突（Spring 的锁异常或带锁错误码的 SQLException）；没有则 null */
    static Throwable lockCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof PessimisticLockingFailureException) {
                // 优先报更底层的带错误码 SQLException（更具体）
                SQLException s = sqlCause(t);
                return s != null && isLockSql(s) ? s : t;
            }
            if (t instanceof SQLException s && isLockSql(s)) return t;
        }
        return null;
    }

    private static boolean isLockSql(SQLException s) {
        return LOCK_ERROR_CODES.contains(s.getErrorCode()) || "40001".equals(s.getSQLState());
    }

    /** deadlock / lock-timeout，用于日志 */
    static String kind(Throwable lock) {
        if (lock instanceof CannotAcquireLockException) return "lock-timeout";
        if (lock instanceof SQLException s && TIMEOUT_CODES.contains(s.getErrorCode())) return "lock-timeout";
        if (lock instanceof SQLException s && "HYT00".equals(s.getSQLState())) return "lock-timeout";
        return "deadlock";
    }

    private static String codes(Throwable t) {
        return t instanceof SQLException s ? " sqlState=" + s.getSQLState() + " errorCode=" + s.getErrorCode() : "";
    }

    static SQLException sqlCause(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause())
            if (t instanceof SQLException s) return s;
        return null;
    }

    private static PessimisticLockingFailureException asLockFailure(RuntimeException e) {
        return e instanceof PessimisticLockingFailureException p ? p
                : new CannotAcquireLockException("锁冲突，重试后仍失败", e);
    }
}
