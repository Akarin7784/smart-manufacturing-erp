package com.akarin.erp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 真正多线程：两笔核销同时打同一张应付单，金额之和超过余额，只能成功一笔 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentSettlementIntegrationTest extends ApiTestSupport {

    @RepeatedTest(5)
    void concurrentSettlementsCannotOverSettle() throws Exception {
        String t = login("admin", "admin123");
        long mat = mdm(t, "materials", "M"), sup = mdm(t, "suppliers", "S"), wh = mdm(t, "warehouses", "W");
        JsonNode order = ok(t, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":10,\"price\":10}]}").path("order");
        long id = order.path("id").asLong();
        long ap = ok(t, "POST", "/api/purchase/orders/" + id + "/submit", null).path("approvalId").asLong();
        ok(t, "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":true}");
        ok(t, "POST", "/api/purchase/orders/" + id + "/receive", null);
        long billId = ok(t, "GET", "/api/finance/bills?sourceBizNo=" + order.path("orderNo").asText(), null)
                .path("records").get(0).path("id").asLong();                       // 应付 100.00
        // 两张独立付款单，避免冲突落在付款单上，只考察单据层面的并发控制
        long p1 = ok(t, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":100}").path("id").asLong();
        long p2 = ok(t, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":100}").path("id").asLong();
        String[] amounts = {"70", "60"};                                           // 70 + 60 > 100
        long[] pays = {p1, p2};

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String body = "{\"requestNo\":\"" + UUID.randomUUID() + "\",\"paymentId\":" + pays[i] +
                        ",\"billId\":" + billId + ",\"amount\":" + amounts[i] + "}";
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return code(t, "POST", "/api/finance/settlements", body);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) codes.add(f.get(30, TimeUnit.SECONDS));
            // 一笔成功；另一笔要么乐观锁冲突 409，要么读到新余额后超额 400
            assertThat(codes).containsOnlyOnce(0);
            assertThat(codes).anyMatch(c -> c == 409 || c == 400);
        } finally {
            pool.shutdownNow();
        }

        JsonNode bill = ok(t, "GET", "/api/finance/bills/" + billId, null);
        assertThat(bill.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(bill.path("settledAmount").decimalValue().compareTo(new java.math.BigDecimal("70")) == 0
                || bill.path("settledAmount").decimalValue().compareTo(new java.math.BigDecimal("60")) == 0).isTrue();
        assertThat(ok(t, "GET", "/api/finance/settlements?billId=" + billId, null).path("total").asInt()).isEqualTo(1);
        long applied = ok(t, "GET", "/api/finance/payments?partnerId=" + sup, null).path("records").findValues("appliedAmount")
                .stream().filter(v -> v.decimalValue().signum() > 0).count();
        assertThat(applied).isEqualTo(1);
    }
}
