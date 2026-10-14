package com.akarin.erp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.akarin.erp.common.tx.TxRetry;
import com.akarin.erp.inventory.mapper.StockMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.h2.api.ErrorCode;
import org.h2.message.DbException;
import org.h2.mvstore.DataUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * #18(d)(e2)(f)：钩子让库存加锁读连续抛死锁 / 回滚失败，验证 TxRetry 重试 3 次后返回 HTTP 409 + 40900（不是 500），
 * 事务整体回滚（订单仍 APPROVED、库存不变），每次重试都有 WARN 日志。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TxRetryExhaustedIntegrationTest extends ApiTestSupport {
    static final AtomicInteger failuresLeft = new AtomicInteger();
    static volatile Supplier<RuntimeException> failure;

    @TestConfiguration
    static class Hook {
        @Bean BeanPostProcessor forcedLockFailure() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof StockMapper)) return bean;
                    return Proxy.newProxyInstance(StockMapper.class.getClassLoader(), new Class<?>[]{StockMapper.class}, (px, m, args) -> {
                        if (m.getName().equals("selectOne") && failure != null && failuresLeft.getAndDecrement() > 0) throw failure.get();
                        try { return m.invoke(bean, args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
                    });
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    ListAppender<ILoggingEvent> log;
    String admin;
    long wh, mat, sup;

    @BeforeEach void setUp() throws Exception {
        log = new ListAppender<>(); log.start();
        ((Logger) LoggerFactory.getLogger(TxRetry.class)).addAppender(log);
        admin = loginAdmin();
        wh = mdm(admin, "warehouses", "XW"); mat = mdm(admin, "materials", "XM"); sup = mdm(admin, "suppliers", "XS");
        ok(admin, "POST", "/api/inventory/in", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"10\"}");
    }

    @AfterEach void tearDown() {
        failure = null; failuresLeft.set(0);
        ((Logger) LoggerFactory.getLogger(TxRetry.class)).detachAppender(log);
    }

    long approvedOrder() throws Exception {
        long id = ok(admin, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"1\",\"price\":\"1\"}]}").path("order").path("id").asLong();
        jdbc.update("UPDATE pur_order SET status='APPROVED' WHERE id=?", id);
        return id;
    }

    void assertExhaustedIs409(Supplier<RuntimeException> f, String kind, String lockClass) throws Exception {
        long id = approvedOrder();
        failure = f; failuresLeft.set(3);
        var res = mvc.perform(post("/api/purchase/orders/" + id + "/receive").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(res.getStatus()).as("重试耗尽 → HTTP 409，绝不 500").isEqualTo(409);
        assertThat(res.getContentAsString()).contains("\"code\":40900");
        assertThat(failuresLeft.get()).as("恰好尝试了 3 次").isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT qty FROM inv_stock WHERE material_id=? AND warehouse_id=?", BigDecimal.class, mat, wh)).isEqualByComparingTo("10");
        assertThat(log.list).hasSize(3).allSatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            // 日志写的是触发重试的真实锁异常及类别，而不是外层包装（TransactionSystemException / UncategorizedSQLException）
            assertThat(e.getFormattedMessage()).contains("op=purchase.receive#" + id).contains("kind=" + kind)
                    .contains("exception=" + lockClass + (lockClass.startsWith("org.h2") ? " " : ""))
                    .doesNotContain("exception=" + TransactionSystemException.class.getName())
                    .doesNotContain("exception=" + UncategorizedSQLException.class.getName());
        });
        assertThat(log.list.get(0).getFormattedMessage()).contains("attempt=1/3");
        assertThat(log.list.get(1).getFormattedMessage()).contains("attempt=2/3");
        assertThat(log.list.get(2).getFormattedMessage()).contains("exhausted").contains("attempts=3");
    }

    @Test void deadlockThreeTimesIs409_40900() throws Exception {
        assertExhaustedIs409(() -> new DeadlockLoserDataAccessException("forced deadlock", null), "deadlock",
                DeadlockLoserDataAccessException.class.getName());
    }

    /** 真实 H2 死锁异常（40001）在 SQL 执行时以 UncategorizedSQLException 抛出：cause 链是锁冲突 → 重试 → 409/40900 */
    @Test void uncategorizedWithH2DeadlockCauseRetriedThen409() throws Exception {
        assertExhaustedIs409(() -> new UncategorizedSQLException("StatementCallback", null,
                DbException.get(ErrorCode.DEADLOCK_1, "inv_stock").getSQLException()), "deadlock",
                org.h2.jdbc.JdbcSQLTransactionRollbackException.class.getName());
    }

    /** 真实的 H2 "illegally transitioned from CLOSED to ROLLING_BACK"（50000 / HY000，cause 为 MVStoreException，无锁错误码）：
     *  不重试，保持 500，日志 ERROR 带 op 与异常类，不带 SQL */
    @Test void uncategorizedIllegalTransitionIs500WithErrorLogAndNoSql() throws Exception {
        long id = approvedOrder();
        java.sql.SQLException real = DbException.convert(DataUtils.newMVStoreException(
                DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE, "Transaction was illegally transitioned from {0} to {1}", "CLOSED", "ROLLING_BACK"))
                .getSQLException();
        assertThat(real.getErrorCode()).isEqualTo(50000);
        assertThat(real.getMessage()).contains("illegally transitioned from CLOSED to ROLLING_BACK");
        failure = () -> new UncategorizedSQLException("StatementCallback", "SELECT secret_column FROM inv_stock WHERE id = 42", real);
        failuresLeft.set(1);
        ListAppender<ILoggingEvent> handlerLog = new ListAppender<>(); handlerLog.start();
        Logger h = (Logger) LoggerFactory.getLogger(com.akarin.erp.common.exception.GlobalExceptionHandler.class);
        h.addAppender(handlerLog);
        try {
            var res = mvc.perform(post("/api/purchase/orders/" + id + "/receive").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(500);
            assertThat(res.getContentAsString()).contains("\"code\":500").doesNotContain("secret_column");
        } finally { h.detachAppender(handlerLog); }
        assertThat(failuresLeft.get()).as("只尝试 1 次").isEqualTo(0);
        assertThat(log.list).hasSize(1);
        ILoggingEvent e = log.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.ERROR);
        assertThat(e.getFormattedMessage()).contains("op=purchase.receive#" + id).contains(UncategorizedSQLException.class.getName())
                .contains("errorCode=50000").doesNotContain("secret_column");
        assertThat(e.getThrowableProxy()).as("不附带异常（异常消息里有 SQL）").isNull();
        // #22-6：整个请求只有 TxRetry 一条 ERROR；全局处理器不再重复，INFO 及以上不带堆栈（堆栈只在 DEBUG）
        assertThat(handlerLog.list).noneMatch(ev -> ev.getLevel() == Level.ERROR);
        assertThat(handlerLog.list).filteredOn(ev -> ev.getLevel().isGreaterOrEqual(Level.INFO)).allSatisfy(ev -> {
            assertThat(ev.getFormattedMessage()).doesNotContain("secret_column").doesNotContain("unexpected");
            assertThat(ev.getThrowableProxy()).isNull();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id)).isEqualTo("APPROVED");
    }

    /** 业务异常导致回滚、回滚又失败（TransactionSystemException 带 applicationException）：返回原业务码，0 次重试 */
    @Test void businessExceptionBehindRollbackFailureKeepsBizCodeNoRetry() throws Exception {
        long id = approvedOrder();
        failure = () -> {
            TransactionSystemException t = new TransactionSystemException("JDBC rollback failed");
            t.initApplicationException(new com.akarin.erp.common.exception.BizException("库存不足，当前可用: 0"));
            return t;
        };
        failuresLeft.set(3);
        var res = mvc.perform(post("/api/purchase/orders/" + id + "/receive").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(400);
        assertThat(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("\"code\":400").contains("库存不足");
        assertThat(failuresLeft.get()).as("只尝试 1 次").isEqualTo(2);
        assertThat(log.list).as("没有重试日志").isEmpty();
    }

    /** 锁超时导致回滚、回滚又失败：日志写 lock-timeout 与真实的 H2 超时异常类，不写 TransactionSystemException */
    @Test void lockTimeoutBehindRollbackFailureLogsRealLockType() throws Exception {
        assertExhaustedIs409(() -> {
            TransactionSystemException t = new TransactionSystemException("JDBC rollback failed");
            t.initApplicationException(new org.springframework.dao.CannotAcquireLockException("lock timeout",
                    DbException.get(ErrorCode.LOCK_TIMEOUT_1, "inv_stock").getSQLException()));
            return t;
        }, "lock-timeout", org.h2.jdbc.JdbcSQLTimeoutException.class.getName());
        assertThat(log.list.get(0).getFormattedMessage()).contains("errorCode=50200");
    }

    /** 回滚失败且没有原始异常：500；TxRetry 与全局处理器都只打简短 ERROR（op/类/SQLState/错误码），不带 SQL、不带堆栈 */
    @Test void bareRollbackFailureIs500WithConciseErrorNoStack() throws Exception {
        long id = approvedOrder();
        failure = () -> new TransactionSystemException("JDBC rollback failed: SELECT secret_column FROM inv_stock",
                new java.sql.SQLException("connection broken during SELECT secret_column", "08S01", 0));
        failuresLeft.set(1);
        ListAppender<ILoggingEvent> handlerLog = new ListAppender<>(); handlerLog.start();
        Logger h = (Logger) LoggerFactory.getLogger(com.akarin.erp.common.exception.GlobalExceptionHandler.class);
        h.addAppender(handlerLog);
        try {
            var res = mvc.perform(post("/api/purchase/orders/" + id + "/receive").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(500);
            assertThat(res.getContentAsString()).doesNotContain("secret_column");
        } finally { h.detachAppender(handlerLog); }
        assertThat(failuresLeft.get()).as("只尝试 1 次").isEqualTo(0);
        assertThat(log.list).hasSize(1);
        ILoggingEvent e = log.list.get(0);
        assertThat(e.getLevel()).isEqualTo(Level.ERROR);
        assertThat(e.getFormattedMessage()).contains("op=purchase.receive#" + id).contains(TransactionSystemException.class.getName())
                .contains("sqlState=08S01").doesNotContain("secret_column");
        assertThat(e.getThrowableProxy()).isNull();
        // #22-6：整个请求只有 TxRetry 一条 ERROR；全局处理器不再重复，INFO 及以上不带堆栈（堆栈只在 DEBUG）
        assertThat(handlerLog.list).noneMatch(ev -> ev.getLevel() == Level.ERROR);
        assertThat(handlerLog.list).filteredOn(ev -> ev.getLevel().isGreaterOrEqual(Level.INFO)).allSatisfy(ev -> {
            assertThat(ev.getFormattedMessage()).doesNotContain("secret_column").doesNotContain("unexpected");
            assertThat(ev.getThrowableProxy()).isNull();
        });
    }

    @Test void deadlockTwiceThenSucceeds() throws Exception {
        long id = approvedOrder();
        failure = () -> new DeadlockLoserDataAccessException("forced deadlock", null); failuresLeft.set(2);
        assertThat(ok(admin, "POST", "/api/purchase/orders/" + id + "/receive", null).path("order").path("status").asText()).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT qty FROM inv_stock WHERE material_id=? AND warehouse_id=?", BigDecimal.class, mat, wh)).isEqualByComparingTo("11");
        assertThat(log.list).hasSize(2);
    }
}
