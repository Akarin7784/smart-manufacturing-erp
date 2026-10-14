package com.akarin.erp;

import com.akarin.erp.common.idem.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #22（H2）：取号繁忙 503、并发同 key，以及 Idempotency-Key 行为矩阵 */
@SpringBootTest
@AutoConfigureMockMvc
class DocNoBusyH2Test extends DocNoBusySupport {
    @Test void lockedSequenceRow503() throws Exception { lockedSequenceRowGives503AndMainPoolStaysFree(); }
    @Test void eightConcurrentSameKey() throws Exception { eightConcurrentSameKeyOneDocument(); }

    @Test void noHeaderBehaviourUnchanged() throws Exception {
        setUpRefs();
        String remark = uniq("nh");
        assertThat(post2(admin, "/api/purchase/orders", po(remark, "1", "1"), null).code()).isZero();
        assertThat(post2(admin, "/api/purchase/orders", po(remark, "1", "1"), null).code()).isZero();
        assertThat(poCount(remark)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_idempotency WHERE idem_key LIKE ?", Integer.class, "%" + remark + "%")).isZero();
    }

    @Test void sameKeySameParamsReturnsOriginalEvenWithReorderedFieldsAndTrailingZeros() throws Exception {
        setUpRefs();
        String remark = uniq("same"), key = "k-" + remark;
        Res a = post2(admin, "/api/purchase/orders", po(remark, "3", "1.5"), key);
        assertThat(a.code()).isZero();
        String reordered = "{\"items\":[{\"price\":\"1.50\",\"qty\":\"3.0\",\"materialId\":" + material + "}],\"remark\":\"" + remark
                + "\",\"warehouseId\":" + warehouse + ",\"supplierId\":" + supplier + "}";
        Res b = post2(admin, "/api/purchase/orders", reordered, key);
        assertThat(b.code()).as("字段顺序、1.50 vs 1.5、3.0 vs 3 视为同一请求").isZero();
        assertThat(b.data().path("order").path("id").asLong()).isEqualTo(a.data().path("order").path("id").asLong());
        assertThat(b.data().path("order").path("orderNo").asText()).isEqualTo(a.data().path("order").path("orderNo").asText());
        assertThat(poCount(remark)).isEqualTo(1);
    }

    @Test void sameKeyDifferentParamsIs40902AndDifferentEndpointToo() throws Exception {
        setUpRefs();
        String remark = uniq("diff"), key = "k-" + remark;
        assertThat(post2(admin, "/api/purchase/orders", po(remark, "3", "1"), key).code()).isZero();
        Res r = post2(admin, "/api/purchase/orders", po(remark, "4", "1"), key);
        assertThat(r.status() + "/" + r.code()).isEqualTo("409/40902");
        Res p = post2(admin, "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + supplier + ",\"amount\":\"10\"}", key);
        assertThat(p.status() + "/" + p.code()).as("同 key 用在别的接口也算参数不同").isEqualTo("409/40902");
        assertThat(poCount(remark)).isEqualTo(1);
    }

    @Test void failedRequestDoesNotReserveKey() throws Exception {
        setUpRefs();
        String remark = uniq("fail"), key = "k-" + remark;
        String bad = po(remark, "1", "1").replace("\"supplierId\":" + supplier, "\"supplierId\":987654321");
        assertThat(post2(admin, "/api/purchase/orders", bad, key).code()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_idempotency WHERE idem_key=?", Integer.class, key)).isZero();
        Res ok = post2(admin, "/api/purchase/orders", po(remark, "1", "1"), key);
        assertThat(ok.code()).as("失败不占 key，改正后同 key 正常创建").isZero();
        assertThat(poCount(remark)).isEqualTo(1);
    }

    @Test void inProgressIs40904AndExpiredKeyActsAsNew() throws Exception {
        setUpRefs();
        String remark = uniq("prog"), key = "k-" + remark;
        Object req = om.readValue(po(remark, "1", "1"), com.akarin.erp.purchase.dto.PurchaseOrderCreateReq.class);
        LocalDateTime now = LocalDateTime.now(com.akarin.erp.common.util.NoGenerator.ZONE);
        jdbc.update("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, created_at, expires_at) VALUES ('admin', ?, ?, 'PROCESSING', ?, ?)",
                key, IdempotencyService.hash("purchase.order.create", req), Timestamp.valueOf(now), Timestamp.valueOf(now.plusHours(24)));
        Res r = post2(admin, "/api/purchase/orders", po(remark, "1", "1"), key);
        assertThat(r.status() + "/" + r.code()).isEqualTo("409/40904");
        assertThat(poCount(remark)).isZero();
        // 过期（>24h）的记录：同 key（哪怕参数不同）视为新 key
        jdbc.update("UPDATE sys_idempotency SET expires_at = ? WHERE idem_key = ?", Timestamp.valueOf(now.minusMinutes(1)), key);
        Res n = post2(admin, "/api/purchase/orders", po(remark, "2", "1"), key);
        assertThat(n.code()).isZero();
        assertThat(poCount(remark)).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, result_id, expires_at FROM sys_idempotency WHERE idem_key=?", key);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(((Number) row.get("result_id")).longValue()).isEqualTo(n.data().path("order").path("id").asLong());
        assertThat(((Timestamp) row.get("expires_at")).toLocalDateTime()).isAfter(now.plusHours(23));
    }

    @Test void salesOrderInventoryAndPaymentReplay() throws Exception {
        setUpRefs();
        String tag = uniq("rp");
        String so = "{\"customerId\":" + customer + ",\"warehouseId\":" + warehouse + ",\"remark\":\"" + tag
                + "\",\"items\":[{\"materialId\":" + material + ",\"qty\":\"1\",\"price\":\"2\"}]}";
        String in = "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"5\",\"remark\":\"" + tag + "\"}";
        String out = "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"1\",\"remark\":\"" + tag + "\"}";
        String pay = "{\"paymentType\":\"RECEIPT\",\"partnerId\":" + customer + ",\"amount\":\"10.00\",\"remark\":\"" + tag + "\"}";
        for (var c : List.of(List.of("/api/sales/orders", so, "order"), List.of("/api/inventory/in", in, ""),
                List.of("/api/inventory/out", out, ""), List.of("/api/finance/payments", pay, ""))) {
            String key = "k-" + tag + c.get(0);
            Res a = post2(admin, c.get(0), c.get(1), key), b = post2(admin, c.get(0), c.get(1), key);
            assertThat(a.code()).as(c.get(0)).isZero();
            assertThat(b.code()).as(c.get(0)).isZero();
            var ida = c.get(2).isEmpty() ? a.data().path("id") : a.data().path(c.get(2)).path("id");
            var idb = c.get(2).isEmpty() ? b.data().path("id") : b.data().path(c.get(2)).path("id");
            assertThat(idb.asLong()).as(c.get(0)).isEqualTo(ida.asLong());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sal_order WHERE remark=?", Integer.class, tag)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE remark=?", Integer.class, tag)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fin_payment WHERE remark=?", Integer.class, tag)).isEqualTo(1);
        assertThat(new BigDecimal(jdbc.queryForObject("SELECT qty FROM inv_stock WHERE material_id=? AND warehouse_id=?", String.class, material, warehouse)))
                .as("入 5 出 1，重放不重复记账").isEqualByComparingTo("4");
    }

    @Test void blankOrTooLongKeyIs400() throws Exception {
        setUpRefs();
        assertThat(post2(admin, "/api/purchase/orders", po(uniq("x"), "1", "1"), " ").code()).isEqualTo(400);
        assertThat(post2(admin, "/api/purchase/orders", po(uniq("x"), "1", "1"), "k".repeat(129)).code()).isEqualTo(400);
    }
}
