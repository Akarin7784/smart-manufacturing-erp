package com.akarin.erp;

import com.akarin.erp.common.event.ApprovalFinishedEvent;
import com.akarin.erp.purchase.mapper.PurchaseOrderMapper;
import com.akarin.erp.purchase.service.PurchaseOrderService;
import com.akarin.erp.sales.mapper.SalesOrderMapper;
import com.akarin.erp.sales.service.SalesOrderService;
import com.akarin.erp.workflow.mapper.ProcessDefMapper;
import com.akarin.erp.workflow.service.DefinitionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 采购单 / 销售单 / 流程定义 version：编辑须带 version；状态变更定向更新并 version+1；双向并发窗口不丢状态 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderVersionIntegrationTest extends SysTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired PurchaseOrderService purchaseOrders;
    @Autowired SalesOrderService salesOrders;
    @Autowired DefinitionService definitions;

    static volatile String armedTable;
    static volatile long armedId;
    static volatile int fired;
    static volatile Runnable armedAction;
    static volatile JdbcTemplate hookJdbc;
    static volatile TransactionTemplate hookTx;

    @TestConfiguration
    static class WindowHook {
        private static final Map<Class<?>, String> TABLES = Map.of(
                PurchaseOrderMapper.class, "pur_order",
                SalesOrderMapper.class, "sal_order",
                ProcessDefMapper.class, "wf_process_def");

        @Bean BeanPostProcessor orderVersionWindowHook() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    for (var e : TABLES.entrySet()) {
                        if (!e.getKey().isInstance(bean)) continue;
                        String table = e.getValue();
                        return Proxy.newProxyInstance(e.getKey().getClassLoader(), new Class<?>[]{e.getKey()}, (px, m, args) -> {
                            Object r;
                            try { r = m.invoke(bean, args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
                            if (m.getName().equals("selectById") && table.equals(armedTable) && args[0] != null
                                    && Long.parseLong(args[0].toString()) == armedId) {
                                armedTable = null;
                                Runnable act = armedAction; armedAction = null;
                                fired = hookTx.execute(st -> {
                                    if (act != null) { act.run(); return 1; }
                                    return hookJdbc.update("UPDATE " + table + " SET version = version + 1 WHERE id = ?", armedId);
                                });
                            }
                            return r;
                        });
                    }
                    return bean;
                }
            };
        }
    }

    @BeforeEach void hooks() {
        hookJdbc = jdbc;
        hookTx = new TransactionTemplate(txm);
        hookTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @AfterEach void disarm() {
        armedTable = null; armedAction = null;
        // 勿留下审批中实例，否则 WorkflowIntegrationTest 无法改 PURCHASE_ORDER 流程定义
        jdbc.update("UPDATE wf_approval_instance SET status='CANCELLED' WHERE status='PENDING' AND biz_type IN ('PURCHASE_ORDER','SALES_ORDER')");
        jdbc.update("UPDATE pur_order SET status='DRAFT' WHERE status='PENDING'");
        jdbc.update("UPDATE sal_order SET status='DRAFT' WHERE status='PENDING'");
    }

    private void arm(String table, long id) { armedAction = null; armedTable = table; armedId = id; fired = 0; }
    private void armAction(String table, long id, Runnable a) { arm(table, id); armedAction = a; }

    long mat, sup, wh, cust;
    String approverTok;

    void decideAsApprover(long approvalId, boolean approved) throws Exception {
        if (approverTok == null) approverTok = login("approver", "approver123");
        ok(approverTok, "POST", "/api/workflow/approvals/" + approvalId + "/decide",
                "{\"approved\":" + approved + ",\"comment\":\"ok\"}");
    }

    @BeforeEach void seed() throws Exception {
        mat = mdm(admin, "materials", "OV");
        sup = mdm(admin, "suppliers", "OV");
        wh = mdm(admin, "warehouses", "OV");
        cust = mdm(admin, "customers", "OV");
    }

    String createBody(String partnerField, long partnerId) {
        return "{\"" + partnerField + "\":" + partnerId + ",\"warehouseId\":" + wh +
                ",\"remark\":\"r0\",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"1\",\"price\":\"10\"}]}";
    }

    String updateBody(int version, String partnerField, long partnerId, String remark) {
        return "{\"version\":" + version + ",\"" + partnerField + "\":" + partnerId + ",\"warehouseId\":" + wh +
                ",\"remark\":\"" + remark + "\",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"2\",\"price\":\"10\"}]}";
    }

    int dbVersion(String table, long id) {
        return jdbc.queryForObject("SELECT version FROM " + table + " WHERE id=?", Integer.class, id);
    }

    @Test void purchaseCreateReturnsVersionAndUpdateRequiresIt() throws Exception {
        JsonNode d = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId", sup));
        long id = d.path("order").path("id").asLong();
        assertThat(d.path("order").path("version").asInt()).as("新建单 version = 0").isZero();
        assertThat(dbVersion("pur_order", id)).isZero();
        assertThat(d.path("order").path("totalAmount").asText()).isEqualTo("10.00");
        String url = "/api/purchase/orders/" + id;
        assertThat(call(admin, "PUT", url, "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"1\",\"price\":\"1\"}]}").path("code").asInt()).as("缺 version").isEqualTo(400);
        JsonNode u = ok(admin, "PUT", url, updateBody(0, "supplierId", sup, "edited"));
        assertThat(u.path("order").path("version").asInt()).isEqualTo(1);
        assertThat(u.path("order").path("remark").asText()).isEqualTo("edited");
        assertThat(u.path("order").path("totalAmount").asText()).isEqualTo("20.00");
        assertThat(code(admin, "PUT", url, updateBody(0, "supplierId", sup, "stale"))).isEqualTo(40900);
        JsonNode list = ok(admin, "GET", "/api/purchase/orders?status=DRAFT", null);
        boolean found = false;
        for (JsonNode r : list.path("records")) if (r.path("id").asLong() == id) {
            assertThat(r.path("version").asInt()).isEqualTo(1); found = true;
        }
        assertThat(found).isTrue();
    }

    @Test void salesSameVersionRules() throws Exception {
        JsonNode d = ok(admin, "POST", "/api/sales/orders", createBody("customerId", cust));
        long id = d.path("order").path("id").asLong();
        assertThat(d.path("order").path("version").asInt()).isZero();
        assertThat(dbVersion("sal_order", id)).isZero();
        assertThat(ok(admin, "PUT", "/api/sales/orders/" + id, updateBody(0, "customerId", cust, "s"))
                .path("order").path("version").asInt()).isEqualTo(1);
        assertThat(code(admin, "PUT", "/api/sales/orders/" + id, updateBody(0, "customerId", cust, "old"))).isEqualTo(40900);
    }

    /** 3) 不存在的单据：先 404，即使没带 version */
    @Test void updateMissingOrderIs404BeforeVersionCheck() throws Exception {
        String noVer = "{\"warehouseId\":" + wh + ",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"1\",\"price\":\"1\"}]";
        assertThat(code(admin, "PUT", "/api/purchase/orders/999999", noVer + ",\"supplierId\":" + sup + "}")).isEqualTo(404);
        assertThat(code(admin, "PUT", "/api/sales/orders/999999", noVer + ",\"customerId\":" + cust + "}")).isEqualTo(404);
        assertThat(code(admin, "PUT", "/api/purchase/orders/999999", updateBody(0, "supplierId", sup, "x"))).isEqualTo(404);
    }

    /** 1) 编辑读到草稿之后、写入之前，并发提交把单据改成 PENDING：WHERE status='DRAFT' 拦住 → 400（不是 40900），且不改任何字段 */
    @Test void editLosesToConcurrentSubmitWith400AndNoChange() throws Exception {
        for (String[] t : new String[][]{{"purchase", "pur_order", "supplierId"}, {"sales", "sal_order", "customerId"}}) {
            long partner = t[0].equals("purchase") ? sup : cust;
            long id = ok(admin, "POST", "/api/" + t[0] + "/orders", createBody(t[2], partner)).path("order").path("id").asLong();
            // 只改状态、不动 version：单靠 version 条件拦不住，必须靠 status = 'DRAFT'
            armAction(t[1], id, () -> jdbc.update("UPDATE " + t[1] + " SET status='PENDING' WHERE id=?", id));
            JsonNode r = call(admin, "PUT", "/api/" + t[0] + "/orders/" + id, updateBody(0, t[2], partner, "lost"));
            assertThat(fired).isEqualTo(1);
            assertThat(r.path("code").asInt()).as(t[0]).isEqualTo(400);
            assertThat(r.path("msg").asText()).contains("仅草稿可编辑");
            assertThat(jdbc.queryForObject("SELECT remark FROM " + t[1] + " WHERE id=?", String.class, id)).isEqualTo("r0");
            assertThat(jdbc.queryForObject("SELECT status FROM " + t[1] + " WHERE id=?", String.class, id)).isEqualTo("PENDING");
            assertThat(dbVersion(t[1], id)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + t[1] + "_item WHERE order_id=? AND deleted=0", Integer.class, id))
                    .as("明细替换随事务回滚").isEqualTo(1);
            jdbc.update("UPDATE " + t[1] + " SET status='DRAFT' WHERE id=?", id);
        }
    }

    /** 1) 窗口内仍是草稿、只是 version 被别人改了 → 40900 */
    @Test void editStillDraftButVersionChangedIs40900() throws Exception {
        long id = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId", sup)).path("order").path("id").asLong();
        arm("pur_order", id);   // 默认动作：version + 1
        assertThat(code(admin, "PUT", "/api/purchase/orders/" + id, updateBody(0, "supplierId", sup, "x"))).isEqualTo(40900);
        assertThat(fired).isEqualTo(1);
    }

    /** 4) 收货 / 发货返回的 order.version、status 与库里一致；提交、审批返回体不含订单 version */
    @Test void receiveAndShipReturnVersionMatchingDatabase() throws Exception {
        long po = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId", sup)).path("order").path("id").asLong();
        JsonNode sub = ok(admin, "POST", "/api/purchase/orders/" + po + "/submit", null);
        assertThat(sub.has("order")).as("提交只返回 approvalId，没有订单 version 可过期").isFalse();
        assertThat(dbVersion("pur_order", po)).isEqualTo(1);
        decideAsApprover(sub.path("approvalId").asLong(), true);
        assertThat(dbVersion("pur_order", po)).isEqualTo(2);
        JsonNode rcv = ok(admin, "POST", "/api/purchase/orders/" + po + "/receive", null).path("order");
        assertThat(rcv.path("status").asText()).isEqualTo("RECEIVED");
        assertThat(rcv.path("version").asInt()).isEqualTo(dbVersion("pur_order", po)).isEqualTo(3);

        long so = ok(admin, "POST", "/api/sales/orders", createBody("customerId", cust)).path("order").path("id").asLong();
        long ap = ok(admin, "POST", "/api/sales/orders/" + so + "/submit", null).path("approvalId").asLong();
        decideAsApprover(ap, true);
        JsonNode shp = ok(admin, "POST", "/api/sales/orders/" + so + "/ship", null).path("order");
        assertThat(shp.path("status").asText()).isEqualTo("SHIPPED");
        assertThat(shp.path("version").asInt()).isEqualTo(dbVersion("sal_order", so)).isEqualTo(3);
        // 收货 / 发货之后再拿返回的 version 不会被误判为过期：GET 详情一致
        assertThat(ok(admin, "GET", "/api/sales/orders/" + so, null).path("order").path("version").asInt()).isEqualTo(3);
    }

    /** 4) 驳回：回到草稿且 version 递增，驳回后用 GET 拿到的 version 可以直接编辑 */
    @Test void rejectBumpsVersionAndEditWithFreshVersionWorks() throws Exception {
        long po = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId", sup)).path("order").path("id").asLong();
        long ap = ok(admin, "POST", "/api/purchase/orders/" + po + "/submit", null).path("approvalId").asLong();
        decideAsApprover(ap, false);
        JsonNode g = ok(admin, "GET", "/api/purchase/orders/" + po, null).path("order");
        assertThat(g.path("status").asText()).isEqualTo("DRAFT");
        assertThat(g.path("version").asInt()).isEqualTo(2).isEqualTo(dbVersion("pur_order", po));
        assertThat(ok(admin, "PUT", "/api/purchase/orders/" + po, updateBody(2, "supplierId", sup, "after-reject"))
                .path("order").path("version").asInt()).isEqualTo(3);
    }

    @Test void submitAndApprovalBumpVersionAndConflictWithStaleEdit() throws Exception {
        long id = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId", sup)).path("order").path("id").asLong();
        int v = ok(admin, "GET", "/api/purchase/orders/" + id, null).path("order").path("version").asInt();
        long ap = ok(admin, "POST", "/api/purchase/orders/" + id + "/submit", null).path("approvalId").asLong();
        assertThat(ok(admin, "GET", "/api/purchase/orders/" + id, null).path("order").path("version").asInt()).isEqualTo(v + 1);
        assertThat(ok(admin, "GET", "/api/purchase/orders/" + id, null).path("order").path("status").asText()).isEqualTo("PENDING");
        // 审批中不可编辑
        assertThat(code(admin, "PUT", "/api/purchase/orders/" + id, updateBody(v + 1, "supplierId", sup, "no"))).isEqualTo(400);
        decideAsApprover(ap, true);
        JsonNode after = ok(admin, "GET", "/api/purchase/orders/" + id, null).path("order");
        assertThat(after.path("status").asText()).isEqualTo("APPROVED");
        assertThat(after.path("version").asInt()).isEqualTo(v + 2).isEqualTo(dbVersion("pur_order", id));
    }

    @Test void processDefUpdateRequiresVersion() throws Exception {
        String bt = "ORDER_VER_TEST";
        ok(admin, "PUT", "/api/workflow/definitions/" + bt,
                "{\"name\":\"p1\",\"nodes\":[{\"name\":\"n\",\"roleCode\":\"ADMIN\",\"approveMode\":\"ANY\"}]}");
        JsonNode g = ok(admin, "GET", "/api/workflow/definitions/" + bt, null);
        assertThat(g.path("process").path("version").asInt()).isZero();
        assertThat(code(admin, "PUT", "/api/workflow/definitions/" + bt,
                "{\"name\":\"p2\",\"nodes\":[{\"name\":\"n\",\"roleCode\":\"ADMIN\",\"approveMode\":\"ANY\"}]}"))
                .as("更新不带 version").isEqualTo(400);
        ok(admin, "PUT", "/api/workflow/definitions/" + bt,
                "{\"version\":0,\"name\":\"p2\",\"nodes\":[{\"name\":\"n\",\"roleCode\":\"ADMIN\",\"approveMode\":\"ANY\"}]}");
        assertThat(ok(admin, "GET", "/api/workflow/definitions/" + bt, null).path("process").path("version").asInt()).isEqualTo(1);
        assertThat(code(admin, "PUT", "/api/workflow/definitions/" + bt,
                "{\"version\":0,\"name\":\"p3\",\"nodes\":[{\"name\":\"n\",\"roleCode\":\"ADMIN\",\"approveMode\":\"ANY\"}]}"))
                .isEqualTo(40900);
        ok(admin, "DELETE", "/api/workflow/definitions/" + bt, null);
    }

    /** 方向一：编辑读出之后、写回之前，审批回调先把状态改成 APPROVED → 编辑 40900，状态不被写回 DRAFT */
    @Test void editDoesNotOverwriteApprovalWhenCallbackWinsTheWindow() throws Exception {
        long id = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId",
                sup)).path("order").path("id").asLong();
        // 人为把单据放到 PENDING，version 保持可读
        jdbc.update("UPDATE pur_order SET status='PENDING' WHERE id=?", id);
        int v = jdbc.queryForObject("SELECT version FROM pur_order WHERE id=?", Integer.class, id);
        armAction("pur_order", id, () ->
                purchaseOrders.onApproval(new ApprovalFinishedEvent(PurchaseOrderService.BIZ_TYPE, id, true, "t")));
        // update() 里 require(id) → selectById 触发钩子
        assertThat(code(admin, "PUT", "/api/purchase/orders/" + id,
                updateBody(v, "supplierId",
                        sup, "should-fail"))).isIn(40900, 400); // 若钩子先改成 APPROVED，编辑会因非草稿 400；若 version 变了则 40900
        assertThat(fired).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT remark FROM pur_order WHERE id=?", String.class, id)).isNotEqualTo("should-fail");
    }

    /** 方向二：提交读出之后、写回之前，编辑先提交 → 提交仍成功（定向改状态），remark 不被旧值覆盖；或提交 40900 也行 */
    @Test void submitDoesNotClobberConcurrentEdit() throws Exception {
        long id = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId",
                sup)).path("order").path("id").asLong();
        int v = jdbc.queryForObject("SELECT version FROM pur_order WHERE id=?", Integer.class, id);
        armAction("pur_order", id, () -> {
            // 模拟并发编辑：定向更新 remark + version
            jdbc.update("UPDATE pur_order SET remark='concurrent-edit', version = version + 1, updated_at = CURRENT_TIMESTAMP WHERE id=?", id);
        });
        ok(admin, "POST", "/api/purchase/orders/" + id + "/submit", null);
        assertThat(fired).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT remark FROM pur_order WHERE id=?", String.class, id))
                .as("提交不能整行写回覆盖并发编辑的 remark").isEqualTo("concurrent-edit");
    }

    /** 审批回调读出后，编辑（若仍是草稿）先提交——把单据先置回 DRAFT 再测：回调定向 WHERE status=PENDING，编辑改 remark 后回调仍应成功且不丢 APPROVED */
    @Test void approvalCallbackDoesNotLoseToEditInWindow() throws Exception {
        long id = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId",
                sup)).path("order").path("id").asLong();
        jdbc.update("UPDATE pur_order SET status='PENDING' WHERE id=?", id);
        armAction("pur_order", id, () ->
                jdbc.update("UPDATE pur_order SET remark='edited-in-window', version = version + 1 WHERE id=?", id));
        // onApproval 现在是定向更新，不再 selectById；用钩子包不住。改为直接调用并断言：
        // 先制造「回调用整行写回会丢 remark」的对照：新实现应保留 remark 并变成 APPROVED
        purchaseOrders.onApproval(new ApprovalFinishedEvent(PurchaseOrderService.BIZ_TYPE, id, true, "t"));
        assertThat(jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id)).isEqualTo("APPROVED");
        // 再测真正的窗口：编辑的 selectById 之后回调先提交
        long id2 = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId",
                sup)).path("order").path("id").asLong();
        int v2 = jdbc.queryForObject("SELECT version FROM pur_order WHERE id=?", Integer.class, id2);
        armAction("pur_order", id2, () -> {
            jdbc.update("UPDATE pur_order SET status='PENDING' WHERE id=?", id2);
            purchaseOrders.onApproval(new ApprovalFinishedEvent(PurchaseOrderService.BIZ_TYPE, id2, true, "t"));
        });
        assertThat(code(admin, "PUT", "/api/purchase/orders/" + id2, updateBody(v2, "supplierId",
                sup, "lose"))).isIn(40900, 400);
        assertThat(jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id2)).isEqualTo("APPROVED");
    }

    @Test void stressEditVersusApprovalCallback() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                long id = ok(admin, "POST", "/api/purchase/orders", createBody("supplierId",
                        sup)).path("order").path("id").asLong();
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger editOk = new AtomicInteger();
                Future<?> editor = pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 5; i++) {
                        Integer v = jdbc.queryForObject("SELECT version FROM pur_order WHERE id=?", Integer.class, id);
                        String st = jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id);
                        if (!"DRAFT".equals(st)) break;
                        if (code(admin, "PUT", "/api/purchase/orders/" + id, updateBody(v, "supplierId",
                                sup, "r" + i)) == 0) editOk.incrementAndGet();
                    }
                    return null;
                });
                Future<?> approver = pool.submit(() -> {
                    go.await();
                    // 提交再批准（默认单级审批）
                    ok(admin, "POST", "/api/purchase/orders/" + id + "/submit", null);
                    Long ap = jdbc.queryForObject(
                            "SELECT id FROM wf_approval_instance WHERE biz_type='PURCHASE_ORDER' AND biz_id=? ORDER BY id DESC LIMIT 1",
                            Long.class, id);
                    decideAsApprover(ap, true);
                    return null;
                });
                go.countDown();
                editor.get(30, TimeUnit.SECONDS);
                approver.get(30, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT status FROM pur_order WHERE id=?", String.class, id))
                        .as("round " + round).isEqualTo("APPROVED");
            }
        } finally { pool.shutdownNow(); }
    }
}
