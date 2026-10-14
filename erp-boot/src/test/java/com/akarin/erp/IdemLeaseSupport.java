package com.akarin.erp;

import com.akarin.erp.common.idem.IdempotencyService;
import com.akarin.erp.common.util.NoGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * #22-9 租期：业务已提交但记录仍 PROCESSING → 租期后重放；租期内 → 40904；
 * 两个实例同时接管 → 只一张单；单据 UNIQUE(idem_user, idem_key)。
 */
abstract class IdemLeaseSupport extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;

    record Res(int status, int code, JsonNode data) {}

    Res post2(String token, String url, String body, String key) throws Exception {
        var req = post(url).contentType(MediaType.APPLICATION_JSON).content(body).header("Authorization", "Bearer " + token);
        if (key != null) req.header(IdempotencyService.HEADER, key);
        var res = mvc.perform(req).andReturn().getResponse();
        JsonNode j = om.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        return new Res(res.getStatus(), j.path("code").asInt(), j.path("data"));
    }

    String admin;
    long supplier, warehouse, material;

    void setUpRefs() throws Exception {
        admin = loginAdmin();
        supplier = mdm(admin, "suppliers", "LS"); warehouse = mdm(admin, "warehouses", "LW");
        material = mdm(admin, "materials", "LM");
    }

    String po(String remark) {
        return "{\"supplierId\":" + supplier + ",\"warehouseId\":" + warehouse + ",\"remark\":\"" + remark
                + "\",\"items\":[{\"materialId\":" + material + ",\"qty\":\"1\",\"price\":\"2\"}]}";
    }

    int poCount(String remark) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pur_order WHERE remark=?", Integer.class, remark);
    }

    LocalDateTime now() { return LocalDateTime.now(NoGenerator.ZONE); }

    /** 1. 单据已提交、记录仍 PROCESSING；租期过后同 key 重放，不建第二张 */
    void committedButProcessingReplaysAfterLease() throws Exception {
        setUpRefs();
        String remark = uniq("cmt"), key = "lease-cmt-" + remark;
        Object req = om.readValue(po(remark), com.akarin.erp.purchase.dto.PurchaseOrderCreateReq.class);
        String hash = IdempotencyService.hash("purchase.order.create", req);
        // 模拟：业务事务已提交，但 mark-done 失败 / 进程崩溃
        jdbc.update("INSERT INTO pur_order (order_no, supplier_id, warehouse_id, status, total_amount, remark, created_by, version, deleted, idem_user, idem_key, idem_hash) "
                        + "VALUES (?, ?, ?, 'DRAFT', 2, ?, 'admin', 0, 0, 'admin', ?, ?)",
                "PO-LEASE-" + remark, supplier, warehouse, remark, key, hash);
        long docId = jdbc.queryForObject("SELECT id FROM pur_order WHERE remark=?", Long.class, remark);
        LocalDateTime n = now();
        jdbc.update("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, result_id, created_at, expires_at, owner_token, lease_until) "
                        + "VALUES ('admin', ?, ?, 'PROCESSING', NULL, ?, ?, 'dead-token', TIMESTAMPADD(SECOND, -5, LOCALTIMESTAMP))",
                key, hash, Timestamp.valueOf(n.minusMinutes(2)), Timestamp.valueOf(n.plusHours(24)));
        Res r = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(r.code()).isZero();
        assertThat(r.data().path("order").path("id").asLong()).isEqualTo(docId);
        assertThat(poCount(remark)).as("不建第二张").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, result_id FROM sys_idempotency WHERE idem_key=?", key);
        assertThat(row.get("status")).isEqualTo("DONE");
        assertThat(((Number) row.get("result_id")).longValue()).isEqualTo(docId);
    }

    /** 2. 原请求还在跑、租期未过 → 409/40904 */
    void retryWhileLeaseValidIs40904() throws Exception {
        setUpRefs();
        String remark = uniq("run"), key = "lease-run-" + remark;
        Object req = om.readValue(po(remark), com.akarin.erp.purchase.dto.PurchaseOrderCreateReq.class);
        LocalDateTime n = now();
        jdbc.update("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, created_at, expires_at, owner_token, lease_until) "
                        + "VALUES ('admin', ?, ?, 'PROCESSING', ?, ?, 'alive', TIMESTAMPADD(SECOND, 60, LOCALTIMESTAMP))",
                key, IdempotencyService.hash("purchase.order.create", req),
                Timestamp.valueOf(n), Timestamp.valueOf(n.plusHours(24)));
        Res r = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(r.status() + "/" + r.code()).isEqualTo("409/40904");
        assertThat(poCount(remark)).isZero();
    }

    /** 3. 两个实例同时接管过期租期 → 只一张单，两边拿到同一张或一方 40904 后重放同一张 */
    void twoReclaimersExactlyOneDocument() throws Exception {
        setUpRefs();
        String remark = uniq("two"), key = "lease-two-" + remark;
        Object req = om.readValue(po(remark), com.akarin.erp.purchase.dto.PurchaseOrderCreateReq.class);
        LocalDateTime n = now();
        jdbc.update("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, created_at, expires_at, owner_token, lease_until) "
                        + "VALUES ('admin', ?, ?, 'PROCESSING', ?, ?, 'old', TIMESTAMPADD(SECOND, -5, LOCALTIMESTAMP))",
                key, IdempotencyService.hash("purchase.order.create", req),
                Timestamp.valueOf(n.minusMinutes(2)), Timestamp.valueOf(n.plusHours(24)));
        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Res>> fs = List.of(
                ex.submit(() -> { go.await(); return post2(admin, "/api/purchase/orders", po(remark), key); }),
                ex.submit(() -> { go.await(); return post2(admin, "/api/purchase/orders", po(remark), key); }));
        go.countDown();
        Set<Long> ids = new HashSet<>();
        for (Future<Res> f : fs) {
            Res r = f.get(30, TimeUnit.SECONDS);
            if (r.code() == 0) ids.add(r.data().path("order").path("id").asLong());
            else assertThat(r.status() + "/" + r.code()).isEqualTo("409/40904");
        }
        ex.shutdown();
        assertThat(poCount(remark)).isEqualTo(1);
        long only = jdbc.queryForObject("SELECT id FROM pur_order WHERE remark=?", Long.class, remark);
        // 再重放一次：两边最终都能拿到这一张
        Res again = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(again.code()).isZero();
        assertThat(again.data().path("order").path("id").asLong()).isEqualTo(only);
        if (!ids.isEmpty()) assertThat(ids).containsOnly(only);
    }

    /** 4. 单据 UNIQUE(idem_user, idem_key)：库本身不能落两张同 key 的单 */
    void documentUniqueConstraintBlocksSecondInsert() throws Exception {
        setUpRefs();
        String key = "uk-" + uniq("x");
        jdbc.update("INSERT INTO pur_order (order_no, supplier_id, warehouse_id, status, total_amount, remark, version, deleted, idem_user, idem_key) "
                        + "VALUES (?, ?, ?, 'DRAFT', 1, 'a', 0, 0, 'admin', ?)", "PO-UK-1-" + key, supplier, warehouse, key);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO pur_order (order_no, supplier_id, warehouse_id, status, total_amount, remark, version, deleted, idem_user, idem_key) "
                        + "VALUES (?, ?, ?, 'DRAFT', 1, 'b', 0, 0, 'admin', ?)", "PO-UK-2-" + key, supplier, warehouse, key))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pur_order WHERE idem_user='admin' AND idem_key=?", Integer.class, key)).isEqualTo(1);
        // 未带幂等键（两列都 null）仍可多张
        jdbc.update("INSERT INTO pur_order (order_no, supplier_id, warehouse_id, status, total_amount, remark, version, deleted) "
                + "VALUES (?, ?, ?, 'DRAFT', 1, 'n1', 0, 0)", "PO-UK-N1-" + key, supplier, warehouse);
        jdbc.update("INSERT INTO pur_order (order_no, supplier_id, warehouse_id, status, total_amount, remark, version, deleted) "
                + "VALUES (?, ?, ?, 'DRAFT', 1, 'n2', 0, 0)", "PO-UK-N2-" + key, supplier, warehouse);
    }

    /** 崩溃模拟：PROCESSING 留下、无单据、租期已过 → 同 key 成功且只一张 */
    void crashThenLeaseExpiredCreatesOne() throws Exception {
        setUpRefs();
        String remark = uniq("cr"), key = "lease-cr-" + remark;
        Object req = om.readValue(po(remark), com.akarin.erp.purchase.dto.PurchaseOrderCreateReq.class);
        LocalDateTime n = now();
        jdbc.update("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, created_at, expires_at, owner_token, lease_until) "
                        + "VALUES ('admin', ?, ?, 'PROCESSING', ?, ?, 'crashed', TIMESTAMPADD(SECOND, -30, LOCALTIMESTAMP))",
                key, IdempotencyService.hash("purchase.order.create", req),
                Timestamp.valueOf(n.minusMinutes(5)), Timestamp.valueOf(n.plusHours(24)));
        Res r = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(r.code()).isZero();
        assertThat(poCount(remark)).isEqualTo(1);
        // 接管时 lease_until 用数据库时钟写入：≈ LOCALTIMESTAMP + 租期（2s），与 JVM 时区/时钟无关
        Long diff = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, LOCALTIMESTAMP, lease_until) FROM sys_idempotency WHERE idem_key=?", Long.class, key);
        assertThat(diff).isBetween(-3L, 3L);
        Res r2 = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(r2.data().path("order").path("id").asLong()).isEqualTo(r.data().path("order").path("id").asLong());
    }

    @Autowired org.springframework.boot.autoconfigure.jdbc.DataSourceProperties props;

    /**
     * 慢但还活着的原请求 + 租期过后的接管者：外部会话锁住库存行，原请求 A（手工入库，带 key）卡在 FOR UPDATE 上；
     * 租期（2s）过后同 key 的 B 进来：查不到已提交的单据 → 接管 → 也卡在库存行上；放锁后两者先后提交，
     * 后提交的一方撞单据 UNIQUE(idem_user, idem_key) 回滚，查到对方的流水后重放。
     * 结果：只一条流水、库存只 +1，A 与 B 拿到同一条流水。
     */
    void slowOriginalAndTakeoverOneDocument() throws Exception {
        setUpRefs();
        String tag = uniq("slow"), key = "lease-slow-" + tag;
        String in = "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"1\",\"remark\":\"" + tag + "\"}";
        assertThat(post2(admin, "/api/inventory/in", "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"10\"}", null).code()).isZero();
        ExecutorService ex = Executors.newFixedThreadPool(2);
        Res a, b;
        try (java.sql.Connection ext = java.sql.DriverManager.getConnection(props.determineUrl(), props.determineUsername(), props.determinePassword())) {
            ext.setAutoCommit(false);
            try (var ps = ext.prepareStatement("SELECT qty FROM inv_stock WHERE material_id=? AND warehouse_id=? FOR UPDATE")) {
                ps.setLong(1, material); ps.setLong(2, warehouse); ps.executeQuery().close();
            }
            Future<Res> fa = ex.submit(() -> post2(admin, "/api/inventory/in", in, key));
            Thread.sleep(4300);   // 租期 2s（库里按秒取整再 +1s = 3s）已过，A 仍卡着
            Map<String, Object> rec = jdbc.queryForMap("SELECT status, owner_token FROM sys_idempotency WHERE idem_key=?", key);
            assertThat(rec.get("status")).as("A 仍在处理").isEqualTo("PROCESSING");
            String tokenA = (String) rec.get("owner_token");
            Future<Res> fb = ex.submit(() -> post2(admin, "/api/inventory/in", in, key));
            Thread.sleep(800);
            assertThat(jdbc.queryForObject("SELECT owner_token FROM sys_idempotency WHERE idem_key=?", String.class, key))
                    .as("B 已接管").isNotEqualTo(tokenA);
            ext.rollback();
            a = fa.get(30, TimeUnit.SECONDS); b = fb.get(30, TimeUnit.SECONDS);
        } finally { ex.shutdownNow(); }
        assertThat(a.code()).as("A").isZero();
        assertThat(b.code()).as("B").isZero();
        assertThat(b.data().path("id").asLong()).isEqualTo(a.data().path("id").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE remark=?", Integer.class, tag)).isEqualTo(1);
        assertThat(new java.math.BigDecimal(jdbc.queryForObject("SELECT qty FROM inv_stock WHERE material_id=? AND warehouse_id=?", String.class, material, warehouse)))
                .as("库存只 +1").isEqualByComparingTo("11");
        assertThat(jdbc.queryForObject("SELECT status FROM sys_idempotency WHERE idem_key=?", String.class, key)).isEqualTo("DONE");
    }

    /** BLOCKER：幂等记录已清理（24h）后，同 key 换参数 → 409/40902（不重放旧单据）；同参数 → 重放原单据 */
    void purgedKeyReusedDifferentParamsIs40902SameParamsReplays() throws Exception {
        setUpRefs();
        String remark = uniq("pg"), key = "lease-pg-" + remark;
        Res first = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(first.code()).isZero();
        long id = first.data().path("order").path("id").asLong();
        jdbc.update("DELETE FROM sys_idempotency WHERE idem_key=?", key);   // 模拟 24h 后被清理
        String other = po(remark).replace("\"qty\":\"1\"", "\"qty\":\"9\"");
        Res diff = post2(admin, "/api/purchase/orders", other, key);
        assertThat(diff.status() + "/" + diff.code()).isEqualTo("409/40902");
        assertThat(poCount(remark)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_idempotency WHERE idem_key=?", Integer.class, key)).as("40902 时占位已释放").isZero();
        Res same = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(same.code()).isZero();
        assertThat(same.data().path("order").path("id").asLong()).isEqualTo(id);
        assertThat(poCount(remark)).isEqualTo(1);
        // 租期过期后的查单路径同样比对摘要：伪造一条过期的 PROCESSING（参数 B），单据是参数 A → 40902
        jdbc.update("DELETE FROM sys_idempotency WHERE idem_key=?", key);
        Object reqB = om.readValue(other, com.akarin.erp.purchase.dto.PurchaseOrderCreateReq.class);
        LocalDateTime n = now();
        jdbc.update("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, created_at, expires_at, owner_token, lease_until) "
                        + "VALUES ('admin', ?, ?, 'PROCESSING', ?, ?, 'x', TIMESTAMPADD(SECOND, -5, LOCALTIMESTAMP))",
                key, IdempotencyService.hash("purchase.order.create", reqB), Timestamp.valueOf(n.minusMinutes(2)), Timestamp.valueOf(n.plusHours(24)));
        Res reclaim = post2(admin, "/api/purchase/orders", other, key);
        assertThat(reclaim.status() + "/" + reclaim.code()).isEqualTo("409/40902");
        assertThat(poCount(remark)).isEqualTo(1);
    }

    @Autowired com.akarin.erp.inventory.service.InventoryService inventoryService;

    /** 多行单据：在幂等上下文里生成多条流水（收货 / 发货），流水不带幂等键，不会撞 uk_txn_idem */
    void multiLineMovementsInsideIdemContextDoNotCollide() throws Exception {
        setUpRefs();
        long mat2 = mdm(admin, "materials", "LM2");
        String tag = uniq("ml");
        var lines = List.of(
                new com.akarin.erp.inventory.service.InventoryService.StockLine(material, warehouse, new java.math.BigDecimal("1")),
                new com.akarin.erp.inventory.service.InventoryService.StockLine(mat2, warehouse, new java.math.BigDecimal("2")));
        var txns = com.akarin.erp.common.idem.IdemContext.with("admin", "ml-" + tag, "h", () -> inventoryService.stockInAll(lines, "PURCHASE_RECEIPT", "PO-" + tag));
        assertThat(txns).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE biz_no=? AND idem_key IS NULL", Integer.class, "PO-" + tag)).isEqualTo(2);
        // 端到端：两种物料的采购单收货
        String poBody = "{\"supplierId\":" + supplier + ",\"warehouseId\":" + warehouse + ",\"remark\":\"" + tag
                + "\",\"items\":[{\"materialId\":" + material + ",\"qty\":\"1\",\"price\":\"1\"},{\"materialId\":" + mat2 + ",\"qty\":\"1\",\"price\":\"1\"}]}";
        Res po = post2(admin, "/api/purchase/orders", poBody, "ml-po-" + tag);
        assertThat(po.code()).isZero();
    }

    String raw(String method, String url, String body, String key) throws Exception {
        var req = (method.equals("GET") ? get(url) : post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .header("Authorization", "Bearer " + admin);
        if (key != null) req.header(IdempotencyService.HEADER, key);
        var res = mvc.perform(req).andReturn().getResponse();
        String out = res.getContentAsString(StandardCharsets.UTF_8);
        assertThat(om.readTree(out).path("code").asInt()).as(method + " " + url + " -> " + out).isZero();
        return out;
    }

    /** 幂等内部字段 idemUser / idemKey / idemHash 不出现在任何单据的创建、重放、查询响应里 */
    void idemFieldsNotInResponses() throws Exception {
        setUpRefs();
        long customer = mdm(admin, "customers", "LC");
        String tag = uniq("js");
        java.util.List<String> outs = new java.util.ArrayList<>();
        String poKey = "js-po-" + tag;
        String created = raw("POST", "/api/purchase/orders", po(tag), poKey);
        outs.add(created);
        outs.add(raw("POST", "/api/purchase/orders", po(tag), poKey));  // 重放
        long poId = om.readTree(created).path("data").path("order").path("id").asLong();
        outs.add(raw("GET", "/api/purchase/orders/" + poId, null, null));
        outs.add(raw("GET", "/api/purchase/orders?current=1&size=50", null, null));
        String so = "{\"customerId\":" + customer + ",\"warehouseId\":" + warehouse + ",\"items\":[{\"materialId\":" + material + ",\"qty\":1,\"price\":1}]}";
        String soOut = raw("POST", "/api/sales/orders", so, "js-so-" + tag);
        outs.add(soOut);
        outs.add(raw("POST", "/api/sales/orders", so, "js-so-" + tag));
        long soId = om.readTree(soOut).path("data").path("order").path("id").asLong();
        if (soId > 0) outs.add(raw("GET", "/api/sales/orders/" + soId, null, null));
        String in = "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"1\",\"remark\":\"" + tag + "\"}";
        outs.add(raw("POST", "/api/inventory/in", in, "js-in-" + tag));
        outs.add(raw("POST", "/api/inventory/in", in, "js-in-" + tag));
        String pay = "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + supplier + ",\"amount\":1}";
        outs.add(raw("POST", "/api/finance/payments", pay, "js-pay-" + tag));
        outs.add(raw("POST", "/api/finance/payments", pay, "js-pay-" + tag));
        outs.add(raw("GET", "/api/finance/payments?current=1&size=50", null, null));
        // 确认这些单据确实落了幂等键（字段存在于库里，只是不对外）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pur_order WHERE idem_key=?", Integer.class, poKey)).isEqualTo(1);
        for (String o : outs) assertThat(o.toLowerCase()).doesNotContain("idemuser", "idemkey", "idemhash", "idem_user", "idem_key", "idem_hash");
    }

    int processingLeft(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_idempotency WHERE idem_key=? AND status='PROCESSING'", Integer.class, key);
    }

    /** B1：旧单据 idem_hash 为 NULL，幂等记录已清理 → 5 个创建接口都 409/40902，且不留 PROCESSING */
    void legacyNullHashPurgedIs40902OnAllCreateEndpoints() throws Exception {
        setUpRefs();
        long customer = mdm(admin, "customers", "LC");
        String tag = uniq("nh");
        assertThat(post2(admin, "/api/inventory/in", "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"10\"}", null).code()).isZero();
        String[][] cases = {
            {"/api/purchase/orders", po(tag), "pur_order"},
            {"/api/sales/orders", "{\"customerId\":" + customer + ",\"warehouseId\":" + warehouse + ",\"items\":[{\"materialId\":" + material + ",\"qty\":1,\"price\":1}]}", "sal_order"},
            {"/api/inventory/in", "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"1\"}", "inv_stock_txn"},
            {"/api/inventory/out", "{\"materialId\":" + material + ",\"warehouseId\":" + warehouse + ",\"qty\":\"1\"}", "inv_stock_txn"},
            {"/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + supplier + ",\"amount\":1}", "fin_payment"}};
        for (int n = 0; n < cases.length; n++) {
            String url = cases[n][0], body = cases[n][1], table = cases[n][2], key = "nh-" + n + "-" + tag;
            assertThat(post2(admin, url, body, key).code()).as(url).isZero();
            int docs = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE idem_key=?", Integer.class, key);
            assertThat(docs).isEqualTo(1);
            jdbc.update("UPDATE " + table + " SET idem_hash = NULL WHERE idem_key=?", key);   // 旧版本写入的单据
            jdbc.update("DELETE FROM sys_idempotency WHERE idem_key=?", key);                  // 24h 清理
            Res r = post2(admin, url, body, key);
            assertThat(r.status() + "/" + r.code()).as(url).isEqualTo("409/40902");
            assertThat(processingLeft(key)).as(url + " 不留 PROCESSING").isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE idem_key=?", Integer.class, key)).isEqualTo(1);
            Res again = post2(admin, url, body, key);   // 再试仍 40902，不会卡成 40904
            assertThat(again.status() + "/" + again.code()).as(url).isEqualTo("409/40902");
        }
    }

    /** B1：租期已过的 PROCESSING + 旧单据 NULL 摘要 → 40902，过期记录被清掉 */
    void legacyNullHashLeaseExpiredIs40902() throws Exception {
        setUpRefs();
        String remark = uniq("nl"), key = "nl-" + remark;
        assertThat(post2(admin, "/api/purchase/orders", po(remark), key).code()).isZero();
        jdbc.update("UPDATE pur_order SET idem_hash = NULL WHERE idem_key=?", key);
        jdbc.update("UPDATE sys_idempotency SET status='PROCESSING', result_id=NULL, owner_token='dead', lease_until=TIMESTAMPADD(SECOND, -30, LOCALTIMESTAMP) WHERE idem_key=?", key);
        Res r = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(r.status() + "/" + r.code()).isEqualTo("409/40902");
        assertThat(processingLeft(key)).isZero();
        assertThat(poCount(remark)).isEqualTo(1);
    }

    /** 成功与“从单据重放”路径都不释放记录：之后同 key 同参数仍重放同一张单 */
    void successAndReplayKeepRecord() throws Exception {
        setUpRefs();
        String remark = uniq("kp"), key = "kp-" + remark;
        Res a = post2(admin, "/api/purchase/orders", po(remark), key);
        long id = a.data().path("order").path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_idempotency WHERE idem_key=?", String.class, key)).isEqualTo("DONE");
        Res b = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(b.data().path("order").path("id").asLong()).isEqualTo(id);
        // 从单据重放（清理后撞 uk_pur_idem）：重放后记录是 DONE，再来一次还是同一张
        jdbc.update("DELETE FROM sys_idempotency WHERE idem_key=?", key);
        Res c = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(c.code()).isZero();
        assertThat(c.data().path("order").path("id").asLong()).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_idempotency WHERE idem_key=?", String.class, key)).isEqualTo("DONE");
        Res d = post2(admin, "/api/purchase/orders", po(remark), key);
        assertThat(d.data().path("order").path("id").asLong()).isEqualTo(id);
        assertThat(poCount(remark)).isEqualTo(1);
    }
}
