package com.akarin.erp;

import com.akarin.erp.inventory.mapper.StockMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #13 首次入库并发（库存行还不存在）的确定性交错：
 * 事务 A 执行完“建库存行”语句（新代码 ensureRow / 旧代码 insert）之后、提交之前，钩子把 A 停住；
 * 此时才放事务 B 进来做同一 (物料, 仓库) 的入库；B 读完库存（无行）走到建行语句时放 A 提交。只断言结果。
 * 旧代码：B 的 insert 在 A 提交后撞唯一键 → 409/40900。新代码：B 走 ON DUPLICATE KEY 分支，再 FOR UPDATE 读到 A 的结果并累加。
 */
abstract class FirstInsertRaceSupport extends ApiTestSupport {
    static final Set<String> ROW_CREATORS = Set.of("insert", "ensureRow");

    static volatile boolean armed;
    static final AtomicReference<Thread> first = new AtomicReference<>();
    static volatile CountDownLatch aCreatedRow = new CountDownLatch(1);
    static volatile CountDownLatch releaseA = new CountDownLatch(1);
    static volatile Thread bThread;
    static volatile CountDownLatch bReachedCreate = new CountDownLatch(1);
    static volatile boolean hookFired;

    @TestConfiguration
    static class Hook {
        @Bean BeanPostProcessor firstInsertRaceHook() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof StockMapper)) return bean;
                    return Proxy.newProxyInstance(StockMapper.class.getClassLoader(), new Class<?>[]{StockMapper.class}, (px, m, args) -> {
                        boolean creator = armed && ROW_CREATORS.contains(m.getName());
                        boolean isA = creator && first.compareAndSet(null, Thread.currentThread());
                        boolean isB = creator && !isA && first.get() != Thread.currentThread();
                        if (isB) { bThread = Thread.currentThread(); bReachedCreate.countDown(); }
                        Object r;
                        try { r = m.invoke(bean, args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
                        if (isA) {
                            hookFired = true;
                            aCreatedRow.countDown();
                            releaseA.await(20, TimeUnit.SECONDS);   // A 持有未提交的新行，等 B 卡住
                        }
                        return r;
                    });
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;

    @AfterEach void disarmRace() {
        armed = false; first.set(null); bThread = null; bReachedCreate = new CountDownLatch(1); hookFired = false;
        aCreatedRow = new CountDownLatch(1); releaseA = new CountDownLatch(1);
    }

    void firstReceiveRace(String admin) throws Exception {
        long wh = mdm(admin, "warehouses", "FW"), mat = mdm(admin, "materials", "FM");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock WHERE material_id=? AND warehouse_id=?", Integer.class, mat, wh)).isZero();
        String in1 = "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"1.5\"}";
        String in2 = "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"2.25\"}";
        armed = true;
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            Future<JsonNode> a = ex.submit(() -> call(admin, "POST", "/api/inventory/in", in1));
            assertThat(aCreatedRow.await(20, TimeUnit.SECONDS)).as("钩子触发：A 已建行未提交").isTrue();
            Future<JsonNode> b = ex.submit(() -> call(admin, "POST", "/api/inventory/in", in2));
            // B 已经读过库存（没有行）并走到建行语句：此时 A 仍未提交，两个事务必然重叠。不做锁等待探测或计时判断。
            assertThat(bReachedCreate.await(20, TimeUnit.SECONDS)).as("B 走到建行语句").isTrue();
            releaseA.countDown();
            JsonNode ra = a.get(30, TimeUnit.SECONDS), rb = b.get(30, TimeUnit.SECONDS);
            assertThat(hookFired).isTrue();
            assertThat(ra.path("code").asInt()).as("A: " + ra).isZero();
            assertThat(rb.path("code").asInt()).as("B（首插并发的后来者）不能是 40900: " + rb).isZero();
        } finally {
            releaseA.countDown();
            ex.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock WHERE material_id=? AND warehouse_id=?", Integer.class, mat, wh)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT qty FROM inv_stock WHERE material_id=? AND warehouse_id=?", BigDecimal.class, mat, wh))
                .isEqualByComparingTo("3.75");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE material_id=? AND warehouse_id=?", Integer.class, mat, wh)).isEqualTo(2);
    }
}
