package com.akarin.erp;

import com.akarin.erp.common.idem.IdempotencyService;
import com.akarin.erp.common.util.NoGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** #22：取号池繁忙 503/50301、先取号再开业务事务、Idempotency-Key。H2 与 MySQL 各一个子类 */
abstract class DocNoBusySupport extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSourceProperties props;
    @Autowired DataSource mainDs;

    record Res(int status, int code, JsonNode data) {}

    Res post2(String token, String url, String body, String key) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token);
        if (key != null) req.header(IdempotencyService.HEADER, key);
        var res = mvc.perform(req).andReturn().getResponse();
        JsonNode j = om.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        return new Res(res.getStatus(), j.path("code").asInt(), j.path("data"));
    }

    long supplier, warehouse, material, customer;
    String admin;

    void setUpRefs() throws Exception {
        admin = loginAdmin();
        supplier = mdm(admin, "suppliers", "IS"); warehouse = mdm(admin, "warehouses", "IW");
        material = mdm(admin, "materials", "IM"); customer = mdm(admin, "customers", "IC");
    }

    String po(String remark, String qty, String price) {
        return "{\"supplierId\":" + supplier + ",\"warehouseId\":" + warehouse + ",\"remark\":\"" + remark
                + "\",\"items\":[{\"materialId\":" + material + ",\"qty\":\"" + qty + "\",\"price\":\"" + price + "\"}]}";
    }

    int poCount(String remark) { return jdbc.queryForObject("SELECT COUNT(*) FROM pur_order WHERE remark=?", Integer.class, remark); }

    static String today() { return LocalDate.now(NoGenerator.ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd")); }

    /**
     * 外部会话锁住序列行：16 个并发建单（多于主池 10 个连接）都很快返回 503/50301，主池没有被占满、没有线程排队等主池连接，
     * 不取号的接口（列表查询）照常秒回；放锁后用同一个 Idempotency-Key 重试 → 200，且只建一张单。
     */
    void lockedSequenceRowGives503AndMainPoolStaysFree() throws Exception {
        setUpRefs();
        String key = "PO" + today();
        jdbc.update("INSERT INTO sys_doc_seq (seq_key, val) VALUES (?, 0) ON DUPLICATE KEY UPDATE val = val", key);
        String remark = uniq("busy");
        var pool = ((HikariDataSource) mainDs).getHikariPoolMXBean();
        int max = ((HikariDataSource) mainDs).getMaximumPoolSize();
        ExecutorService ex = Executors.newFixedThreadPool(17);
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicInteger maxActive = new AtomicInteger(), maxWaiting = new AtomicInteger();
        try (Connection ext = DriverManager.getConnection(props.determineUrl(), props.determineUsername(), props.determinePassword())) {
            ext.setAutoCommit(false);
            try (PreparedStatement ps = ext.prepareStatement("SELECT val FROM sys_doc_seq WHERE seq_key = ? FOR UPDATE")) {
                ps.setString(1, key); ps.executeQuery().close();
            }
            List<Future<Res>> fs = new ArrayList<>();
            long t0 = System.nanoTime();
            // 只在“4 个请求正卡在序号行锁上（锁等待 2s）、其余已因取号连接超时（1s）返回”的窗口 1.3s~1.9s 采样：
            // 其他时刻登录校验、幂等占位/释放、引用校验会短暂借还主池连接，不算占用
            Future<?> sampler = ex.submit(() -> {
                while (sampling.get()) {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    if (ms > 1900) return;
                    if (ms >= 1300) {
                        maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
                        maxWaiting.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                    }
                    try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                }
            });
            for (int i = 0; i < 16; i++) {
                String k = "busy-" + remark + "-" + i;
                fs.add(ex.submit(() -> post2(admin, "/api/purchase/orders", po(remark, "1", "2"), k)));
            }
            Thread.sleep(600);
            // 取号被堵时，不取号的接口不受影响
            long g0 = System.nanoTime();
            var list = mvc.perform(get("/api/purchase/orders").header("Authorization", "Bearer " + admin)).andReturn().getResponse();
            assertThat(list.getStatus()).isEqualTo(200);
            assertThat((System.nanoTime() - g0) / 1_000_000).as("列表查询不排队").isLessThan(2000);
            for (Future<Res> f : fs) {
                Res r = f.get(15, TimeUnit.SECONDS);
                assertThat(r.status()).isEqualTo(503);
                assertThat(r.code()).isEqualTo(50301);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("快速失败：拿取号连接 ≤1s、行锁 ≤2s，不是 30s").isLessThan(10_000);
            sampling.set(false); sampler.get(5, TimeUnit.SECONDS);
            assertThat(maxWaiting.get()).as("等号阶段没有请求排队等主池连接").isZero();
            assertThat(maxActive.get()).as("等号阶段不占主池连接（旧代码：每个请求占着 1 个，主池 10 个被占满）").isLessThanOrEqualTo(2).isLessThan(max);
            ext.rollback();
        } finally { sampling.set(false); ex.shutdownNow(); }
        assertThat(poCount(remark)).isZero();
        // 503 后同 key 重试：成功且只一张
        String k = "busy-" + remark + "-0";
        Res ok1 = post2(admin, "/api/purchase/orders", po(remark, "1", "2"), k);
        assertThat(ok1.status()).isEqualTo(200);
        Res ok2 = post2(admin, "/api/purchase/orders", po(remark, "1", "2"), k);
        assertThat(ok2.data().path("order").path("id").asLong()).isEqualTo(ok1.data().path("order").path("id").asLong());
        assertThat(poCount(remark)).isEqualTo(1);
    }

    /** 8 个线程同一个 key 并发建单：只建一张；其余要么返回原单，要么 409/40904 */
    void eightConcurrentSameKeyOneDocument() throws Exception {
        setUpRefs();
        String remark = uniq("c8"), key = "c8-" + remark;
        ExecutorService ex = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(ex.submit(() -> { go.await(); return post2(admin, "/api/purchase/orders", po(remark, "3", "1.5"), key); }));
        go.countDown();
        Set<Long> ids = new HashSet<>();
        int ok = 0;
        for (Future<Res> f : fs) {
            Res r = f.get(30, TimeUnit.SECONDS);
            if (r.code() == 0) { ok++; ids.add(r.data().path("order").path("id").asLong()); }
            else assertThat(r.status() + "/" + r.code()).isEqualTo("409/40904");
        }
        ex.shutdown();
        assertThat(ok).isGreaterThanOrEqualTo(1);
        assertThat(ids).hasSize(1);
        assertThat(poCount(remark)).isEqualTo(1);
        // 结束后同 key 再来：原单
        assertThat(post2(admin, "/api/purchase/orders", po(remark, "3", "1.5"), key).data().path("order").path("id").asLong()).isIn(ids);
    }
}
