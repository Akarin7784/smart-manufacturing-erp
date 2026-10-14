package com.akarin.erp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.akarin.erp.common.tx.TxRetry;
import com.akarin.erp.inventory.mapper.StockMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #18(c) 正反序单据并发：订单 X 明细 [A, B]，订单 Y 明细 [B, A]，同时收货（再同时发货）。
 * 钩子：每个事务第一次加锁读库存行之后停住，直到另一个事务也开始了它的第一次加锁读，才继续。
 * 旧代码按明细顺序加锁：X 持有 A、Y 持有 B，再互相请求 → 死锁（每次都会发生，触发 TxRetry 重试）。
 * 新代码按 (materialId, warehouseId) 排序：Y 的第一次加锁也是 A，只会排队，不会成环。
 * 只断言结果：两次都 200/0（不能 40900、不能 5xx），库存精确，且 TxRetry 没有发生任何重试（没有死锁）。
 */
abstract class ReversedOrderRaceSupport extends ApiTestSupport {
    static volatile boolean armed;
    static final Set<Thread> entered = ConcurrentHashMap.newKeySet();
    static final ThreadLocal<Boolean> firstLockDone = new ThreadLocal<>();
    /** 库存 mapper 上抛出的锁冲突（死锁 / 锁超时）次数：旧代码靠 TxRetry 重试也许能救回来，但死锁本身必须为 0 */
    static final java.util.concurrent.atomic.AtomicInteger lockFailures = new java.util.concurrent.atomic.AtomicInteger();

    @TestConfiguration
    static class Hook {
        @Bean BeanPostProcessor reversedOrderHook() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof StockMapper)) return bean;
                    return Proxy.newProxyInstance(StockMapper.class.getClassLoader(), new Class<?>[]{StockMapper.class}, (px, m, args) -> {
                        boolean first = armed && m.getName().equals("selectOne")
                                && TransactionSynchronizationManager.isSynchronizationActive()
                                && firstLockDone.get() == null;
                        if (first) {
                            firstLockDone.set(Boolean.TRUE);
                            // 每个事务（包括 TxRetry 的重试）各自一轮
                            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override public void afterCompletion(int status) { firstLockDone.remove(); entered.remove(Thread.currentThread()); }
                            });
                            entered.add(Thread.currentThread());
                        }
                        Object r;
                        try { r = m.invoke(bean, args); } catch (InvocationTargetException ex) {
                            if (ex.getCause() instanceof org.springframework.dao.ConcurrencyFailureException) lockFailures.incrementAndGet();
                            throw ex.getCause();
                        }
                        if (first) {
                            long deadline = System.currentTimeMillis() + 3000;
                            while (entered.size() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5);
                        }
                        return r;
                    });
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    ListAppender<ILoggingEvent> retryLog;

    @BeforeEach void captureRetryLog() {
        retryLog = new ListAppender<>();
        retryLog.start();
        ((Logger) LoggerFactory.getLogger(TxRetry.class)).addAppender(retryLog);
    }

    @AfterEach void cleanup() {
        armed = false; entered.clear(); lockFailures.set(0);
        ((Logger) LoggerFactory.getLogger(TxRetry.class)).detachAppender(retryLog);
    }

    long order(String admin, String type, String partnerField, long partner, long wh, long first, String q1, long second, String q2) throws Exception {
        long id = ok(admin, "POST", "/api/" + type + "/orders", "{\"" + partnerField + "\":" + partner + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + first + ",\"qty\":\"" + q1 + "\",\"price\":\"1\"},{\"materialId\":" + second +
                ",\"qty\":\"" + q2 + "\",\"price\":\"1\"}]}").path("order").path("id").asLong();
        // 审批流程与本用例无关：直接置为已审批
        jdbc.update("UPDATE " + (type.equals("purchase") ? "pur_order" : "sal_order") + " SET status='APPROVED' WHERE id=?", id);
        return id;
    }

    BigDecimal qty(long mat, long wh) {
        return jdbc.queryForObject("SELECT qty FROM inv_stock WHERE material_id=? AND warehouse_id=?", BigDecimal.class, mat, wh);
    }

    List<JsonNode> both(String admin, String url1, String url2) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<JsonNode> a = ex.submit(() -> { go.await(); return call(admin, "POST", url1, null); });
            Future<JsonNode> b = ex.submit(() -> { go.await(); return call(admin, "POST", url2, null); });
            go.countDown();
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally { ex.shutdownNow(); }
    }

    void reversedOrderRace(String admin) throws Exception {
        long sup = mdm(admin, "suppliers", "RS"), cus = mdm(admin, "customers", "RC"), wh = mdm(admin, "warehouses", "RW");
        long a = mdm(admin, "materials", "RA"), b = mdm(admin, "materials", "RB");
        // 先建好库存行（排除首插路径，只看加锁顺序）
        ok(admin, "POST", "/api/inventory/in", "{\"materialId\":" + a + ",\"warehouseId\":" + wh + ",\"qty\":\"100\"}");
        ok(admin, "POST", "/api/inventory/in", "{\"materialId\":" + b + ",\"warehouseId\":" + wh + ",\"qty\":\"100\"}");
        long x = order(admin, "purchase", "supplierId", sup, wh, a, "1.5", b, "2.25");
        long y = order(admin, "purchase", "supplierId", sup, wh, b, "3", a, "4");
        long sx = order(admin, "sales", "customerId", cus, wh, a, "5", b, "6");
        long sy = order(admin, "sales", "customerId", cus, wh, b, "7.5", a, "8");
        long txn0 = jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE warehouse_id=?", Long.class, wh);

        lockFailures.set(0);
        armed = true;
        List<JsonNode> recv = both(admin, "/api/purchase/orders/" + x + "/receive", "/api/purchase/orders/" + y + "/receive");
        entered.clear();
        List<JsonNode> ship = both(admin, "/api/sales/orders/" + sx + "/ship", "/api/sales/orders/" + sy + "/ship");
        armed = false;

        for (JsonNode r : List.of(recv.get(0), recv.get(1), ship.get(0), ship.get(1)))
            assertThat(r.path("code").asInt()).as("正反序并发收发：不能 40900 / 500：" + r).isZero();
        assertThat(qty(a, wh)).as("A = 100 + 1.5 + 4 - 5 - 8").isEqualByComparingTo("92.5");
        assertThat(qty(b, wh)).as("B = 100 + 2.25 + 3 - 6 - 7.5").isEqualByComparingTo("91.75");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE warehouse_id=?", Long.class, wh) - txn0).isEqualTo(8);
        assertThat(lockFailures.get()).as("固定加锁顺序后库存加锁读不应出现死锁 / 锁超时").isZero();
        assertThat(retryLog.list).as("固定加锁顺序后不应出现死锁 / 重试：" + retryLog.list).isEmpty();
    }
}
