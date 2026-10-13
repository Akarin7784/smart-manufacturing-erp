package com.akarin.erp;

import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 + 多线程并发。为了不 flaky：在 ArapBillMapper.selectById 返回后加一道 CyclicBarrier，
 * 保证两个线程都读到同一版本的单据后再继续，从而稳定触发乐观锁冲突，而不是偶尔串行执行。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentSettlementIntegrationTest extends ApiTestSupport {
    static volatile CyclicBarrier gate;
    static volatile Long gateBillId;

    @TestConfiguration
    static class GateConfig {
        @Bean
        static BeanPostProcessor arapBillMapperGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof ArapBillMapper target) || Proxy.isProxyClass(bean.getClass()) && name.startsWith("gated")) return bean;
                    return Proxy.newProxyInstance(ArapBillMapper.class.getClassLoader(), new Class<?>[]{ArapBillMapper.class}, (p, m, args) -> {
                        Object r;
                        try { r = m.invoke(target, args); } catch (InvocationTargetException e) { throw e.getCause(); }
                        CyclicBarrier g = gate;
                        if (g != null && m.getName().equals("selectById") && args != null && args[0].equals(gateBillId)) {
                            g.await(10, TimeUnit.SECONDS);
                        }
                        return r;
                    });
                }
            };
        }
    }

    String t;

    @BeforeEach void setUp() throws Exception { t = login("admin", "admin123"); apr = login("approver", "approver123"); }
    String apr;   // 申请人不能审批自己的单据
    @AfterEach void tearDown() { gate = null; gateBillId = null; }

    record Ctx(long supplierId, long billId, long materialId, long warehouseId) {}

    Ctx receivedPurchase(String qty, String price) throws Exception {
        long mat = mdm(t, "materials", "M"), sup = mdm(t, "suppliers", "S"), wh = mdm(t, "warehouses", "W");
        JsonNode order = ok(t, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":" + qty + ",\"price\":" + price + "}]}").path("order");
        long id = order.path("id").asLong();
        long ap = ok(t, "POST", "/api/purchase/orders/" + id + "/submit", null).path("approvalId").asLong();
        ok(apr, "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":true}");
        ok(t, "POST", "/api/purchase/orders/" + id + "/receive", null);
        long billId = ok(t, "GET", "/api/finance/bills?sourceBizNo=" + order.path("orderNo").asText(), null)
                .path("records").get(0).path("id").asLong();
        return new Ctx(sup, billId, mat, wh);
    }

    long payment(long sup, String amount) throws Exception {
        return ok(t, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":" + amount + "}").path("id").asLong();
    }

    /** 两个请求同时起跑，返回各自 body.code（顺序与入参一致） */
    List<Integer> race(String url, String... bodies) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(bodies.length);
        CountDownLatch ready = new CountDownLatch(bodies.length), go = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (String b : bodies) fs.add(pool.submit(() -> { ready.countDown(); go.await(); return code(t, "POST", url, b); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> out = new ArrayList<>();
            for (Future<Integer> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    static String settle(String req, long pay, long bill, String amt) {
        return "{\"requestNo\":\"" + req + "\",\"paymentId\":" + pay + ",\"billId\":" + bill + ",\"amount\":" + amt + "}";
    }

    @RepeatedTest(5)
    void differentRequestNos_overBalance_onlyOneSucceeds_other40900() throws Exception {
        Ctx c = receivedPurchase("10", "10");                     // 应付 100.00
        long p1 = payment(c.supplierId(), "100"), p2 = payment(c.supplierId(), "100");
        gate = new CyclicBarrier(2); gateBillId = c.billId();
        List<Integer> codes = race("/api/finance/settlements",
                settle(UUID.randomUUID().toString(), p1, c.billId(), "70"),
                settle(UUID.randomUUID().toString(), p2, c.billId(), "60"));
        gate = null;
        assertThat(codes).containsExactlyInAnyOrder(0, ErrorCode.CONCURRENT_MODIFICATION.getCode());
        BigDecimal expected = new BigDecimal(codes.get(0) == 0 ? "70" : "60");
        JsonNode bill = ok(t, "GET", "/api/finance/bills/" + c.billId(), null);
        assertThat(new java.math.BigDecimal(bill.path("settledAmount").asText())).isEqualByComparingTo(expected);
        assertThat(bill.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(ok(t, "GET", "/api/finance/settlements?billId=" + c.billId(), null).path("total").asInt()).isEqualTo(1);
        // 失败那笔的付款单未被占用
        long loser = codes.get(0) == 0 ? p2 : p1;
        for (JsonNode p : ok(t, "GET", "/api/finance/payments?partnerId=" + c.supplierId(), null).path("records"))
            if (p.path("id").asLong() == loser) assertThat(new java.math.BigDecimal(p.path("appliedAmount").asText())).isEqualByComparingTo("0");
    }

    @RepeatedTest(5)
    void sameRequestNoSubmittedConcurrently_onlyOneSettlementRecord() throws Exception {
        Ctx c = receivedPurchase("10", "10");
        long p = payment(c.supplierId(), "100");
        String body = settle(UUID.randomUUID().toString(), p, c.billId(), "30");
        gate = new CyclicBarrier(2); gateBillId = c.billId();
        List<Integer> codes = race("/api/finance/settlements", body, body);
        gate = null;
        assertThat(codes).contains(0);
        assertThat(codes).allMatch(x -> x == 0 || x == ErrorCode.CONCURRENT_MODIFICATION.getCode());
        assertThat(ok(t, "GET", "/api/finance/settlements?billId=" + c.billId(), null).path("total").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(ok(t, "GET", "/api/finance/bills/" + c.billId(), null).path("settledAmount").asText())).isEqualByComparingTo("30");
        // 冲突方重放同一请求号 → 幂等返回成功，且不重复核销
        assertThat(code(t, "POST", "/api/finance/settlements", body)).isZero();
        assertThat(new java.math.BigDecimal(ok(t, "GET", "/api/finance/bills/" + c.billId(), null).path("settledAmount").asText())).isEqualByComparingTo("30");
    }

    @RepeatedTest(3)
    void sameRequestNoDifferentParamsConcurrently_loserGets40902() throws Exception {
        Ctx c = receivedPurchase("10", "10");
        long p = payment(c.supplierId(), "100");
        String no = UUID.randomUUID().toString();
        gate = new CyclicBarrier(2); gateBillId = c.billId();
        List<Integer> codes = race("/api/finance/settlements", settle(no, p, c.billId(), "30"), settle(no, p, c.billId(), "20"));
        gate = null;
        assertThat(codes).containsExactlyInAnyOrder(0, ErrorCode.IDEMPOTENCY_KEY_REUSED.getCode());
        assertThat(ok(t, "GET", "/api/finance/settlements?billId=" + c.billId(), null).path("total").asInt()).isEqualTo(1);
    }

    @RepeatedTest(3)
    void sameRequestNoSameParams10Concurrent_allReturnSameResult_settledOnce() throws Exception {
        Ctx c = receivedPurchase("10", "10");
        long p = payment(c.supplierId(), "100");
        String body = settle(UUID.randomUUID().toString(), p, c.billId(), "30");
        int n = 10;
        gate = new CyclicBarrier(n); gateBillId = c.billId();   // 10 个请求都读到同一版本单据后再继续，稳定触发乐观锁冲突
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<JsonNode> results = new ArrayList<>();
        try {
            CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
            List<Future<JsonNode>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) fs.add(pool.submit(() -> { ready.countDown(); go.await(); return call(t, "POST", "/api/finance/settlements", body); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<JsonNode> f : fs) results.add(f.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            gate = null;
        }
        assertThat(results).allSatisfy(r -> assertThat(r.path("code").asInt()).as(r.toString()).isZero());
        assertThat(results.stream().map(r -> r.path("data").path("id").asLong()).distinct()).as("返回的是同一条核销记录").hasSize(1);
        assertThat(ok(t, "GET", "/api/finance/settlements?billId=" + c.billId(), null).path("total").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(ok(t, "GET", "/api/finance/bills/" + c.billId(), null).path("settledAmount").asText())).isEqualByComparingTo("30");
    }

    @RepeatedTest(3)
    void concurrentDuplicateReceiveAndShip_noServerError() throws Exception {
        long mat = mdm(t, "materials", "M"), sup = mdm(t, "suppliers", "S"), cus = mdm(t, "customers", "C"), wh = mdm(t, "warehouses", "W");
        JsonNode po = ok(t, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":10,\"price\":1}]}").path("order");
        long poId = po.path("id").asLong();
        ok(apr, "POST", "/api/workflow/approvals/" + ok(t, "POST", "/api/purchase/orders/" + poId + "/submit", null).path("approvalId").asLong() + "/decide", "{\"approved\":true}");
        List<Integer> r = race("/api/purchase/orders/" + poId + "/receive", "", "");
        assertThat(r).containsOnlyOnce(0).allMatch(x -> x == 0 || x == 400 || x == ErrorCode.CONCURRENT_MODIFICATION.getCode());
        assertThat(ok(t, "GET", "/api/finance/bills?sourceBizNo=" + po.path("orderNo").asText(), null).path("total").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(ok(t, "GET", "/api/inventory/stocks?materialId=" + mat + "&warehouseId=" + wh, null).path("records").get(0).path("qty").asText())).isEqualByComparingTo("10");

        JsonNode so = ok(t, "POST", "/api/sales/orders", "{\"customerId\":" + cus + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":4,\"price\":2}]}").path("order");
        long soId = so.path("id").asLong();
        ok(apr, "POST", "/api/workflow/approvals/" + ok(t, "POST", "/api/sales/orders/" + soId + "/submit", null).path("approvalId").asLong() + "/decide", "{\"approved\":true}");
        List<Integer> s = race("/api/sales/orders/" + soId + "/ship", "", "");
        assertThat(s).containsOnlyOnce(0).allMatch(x -> x == 0 || x == 400 || x == ErrorCode.CONCURRENT_MODIFICATION.getCode());
        assertThat(ok(t, "GET", "/api/finance/bills?sourceBizNo=" + so.path("orderNo").asText(), null).path("total").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(ok(t, "GET", "/api/inventory/stocks?materialId=" + mat + "&warehouseId=" + wh, null).path("records").get(0).path("qty").asText())).isEqualByComparingTo("6");
    }
}
