package com.akarin.erp;

import com.akarin.erp.workflow.entity.NodeDef;
import com.akarin.erp.workflow.entity.ProcessDef;
import com.akarin.erp.workflow.mapper.NodeDefMapper;
import com.akarin.erp.workflow.mapper.ProcessDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 多级审批：顺序节点、或签、会签、金额条件、驳回回草稿、越权、待我审批、审批记录 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIntegrationTest extends SysTestSupport {
    @Autowired ProcessDefMapper processMapper;
    @Autowired NodeDefMapper nodeMapper;

    Role mgr, fin, gm;
    User buyer, m1, f1, f2, g1;
    long sup, wh, mat;

    @BeforeEach void setUpPeople() throws Exception {
        Role buyerRole = role("BUYER", "purchase:write", "inventory:write", "mdm:write");
        mgr = role("MGR"); fin = role("FIN"); gm = role("GM");
        buyer = user(buyerRole); m1 = user(mgr); f1 = user(fin); f2 = user(fin); g1 = user(gm);
        sup = mdm(admin, "suppliers", "S"); wh = mdm(admin, "warehouses", "W"); mat = mdm(admin, "materials", "M");
        // 采购：部门经理（或签）→ 财务（会签，2 人）→ 金额 > 1000 时总经理（或签）
        ok(admin, "PUT", "/api/workflow/definitions/PURCHASE_ORDER", "{\"name\":\"采购审批\",\"nodes\":[" +
                "{\"name\":\"部门经理\",\"roleCode\":\"" + mgr.code() + "\",\"approveMode\":\"ANY\"}," +
                "{\"name\":\"财务会签\",\"roleCode\":\"" + fin.code() + "\",\"approveMode\":\"ALL\"}," +
                "{\"name\":\"总经理\",\"roleCode\":\"" + gm.code() + "\",\"approveMode\":\"ANY\",\"minAmount\":1000}]}");
    }

    /** 直接删定义，避免影响同一 JVM 内其他测试（它们走默认单级审批） */
    @AfterEach void dropDefinition() {
        ProcessDef p = processMapper.selectOne(new LambdaQueryWrapper<ProcessDef>().eq(ProcessDef::getBizType, "PURCHASE_ORDER"));
        if (p != null) {
            nodeMapper.delete(new LambdaQueryWrapper<NodeDef>().eq(NodeDef::getProcessId, p.getId()));
            processMapper.hardDelete(p.getId());
        }
    }

    record Order(long id, long approvalId) {}

    Order submitOrder(String bt, String qty, String price) throws Exception {
        long id = ok(bt, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":" + qty + ",\"price\":" + price + "}]}").path("order").path("id").asLong();
        long ap = ok(bt, "POST", "/api/purchase/orders/" + id + "/submit", null).path("approvalId").asLong();
        return new Order(id, ap);
    }

    JsonNode decide(String t, long ap, boolean approved) throws Exception {
        return call(t, "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":" + approved + ",\"comment\":\"ok\"}");
    }

    List<Long> todo(String t) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : ok(t, "GET", "/api/workflow/approvals/todo", null)) ids.add(n.path("id").asLong());
        return ids;
    }

    String status(String t, long orderId) throws Exception {
        return ok(t, "GET", "/api/purchase/orders/" + orderId, null).path("order").path("status").asText();
    }

    @Test void twoLevelApprovalThenReceive() throws Exception {
        String bt = buyer.token(this), mt = m1.token(this), f1t = f1.token(this), f2t = f2.token(this);
        Order o = submitOrder(bt, "5", "10");                 // 50 元，不触发总经理节点

        assertThat(todo(mt)).contains(o.approvalId());
        assertThat(todo(f1t)).doesNotContain(o.approvalId());
        assertThat(decide(f1t, o.approvalId(), true).path("code").asInt()).as("越权：非当前节点").isEqualTo(403);
        assertThat(decide(admin, o.approvalId(), true).path("code").asInt()).as("管理员不在流程角色内").isEqualTo(403);
        assertThat(code(bt, "POST", "/api/purchase/orders/" + o.id() + "/receive", null)).as("未审批不能收货").isEqualTo(400);

        assertThat(decide(mt, o.approvalId(), true).path("data").path("currentIndex").asInt()).isEqualTo(1);
        assertThat(decide(mt, o.approvalId(), true).path("code").asInt()).as("经理不能审财务节点").isEqualTo(403);
        assertThat(todo(f1t)).contains(o.approvalId());

        decide(f1t, o.approvalId(), true);
        assertThat(status(bt, o.id())).as("会签未完成").isEqualTo("PENDING");
        assertThat(decide(f1t, o.approvalId(), true).path("code").asInt()).as("重复审批").isEqualTo(400);
        assertThat(todo(f1t)).doesNotContain(o.approvalId());
        assertThat(todo(f2t)).contains(o.approvalId());
        assertThat(decide(f2t, o.approvalId(), true).path("data").path("status").asText()).isEqualTo("APPROVED");
        assertThat(status(bt, o.id())).isEqualTo("APPROVED");

        JsonNode detail = ok(bt, "GET", "/api/workflow/approvals/" + o.approvalId(), null);
        assertThat(detail.path("plan").size()).as("金额未超阈值，跳过总经理").isEqualTo(2);
        assertThat(detail.path("records").size()).isEqualTo(3);
        assertThat(detail.path("records").get(0).path("approver").asText()).isEqualTo(m1.username());
        assertThat(detail.path("currentNode").isNull()).isTrue();
        assertThat(ok(bt, "GET", "/api/workflow/approvals?bizType=PURCHASE_ORDER&bizId=" + o.id(), null).size()).isEqualTo(1);

        assertThat(ok(bt, "POST", "/api/purchase/orders/" + o.id() + "/receive", null).path("order").path("status").asText()).isEqualTo("RECEIVED");
        JsonNode stock = ok(bt, "GET", "/api/inventory/stocks?materialId=" + mat + "&warehouseId=" + wh, null).path("records").get(0);
        assertThat(new java.math.BigDecimal(stock.path("qty").asText())).isEqualByComparingTo("5");
    }

    @Test void amountAboveThresholdAddsGmNode() throws Exception {
        String bt = buyer.token(this);
        Order o = submitOrder(bt, "100", "20");               // 2000 元 > 1000
        decide(m1.token(this), o.approvalId(), true);
        decide(f1.token(this), o.approvalId(), true);
        decide(f2.token(this), o.approvalId(), true);
        assertThat(status(bt, o.id())).isEqualTo("PENDING");
        JsonNode d = ok(bt, "GET", "/api/workflow/approvals/" + o.approvalId(), null);
        assertThat(d.path("plan").size()).isEqualTo(3);
        assertThat(d.path("currentNode").path("name").asText()).isEqualTo("总经理");
        assertThat(todo(g1.token(this))).contains(o.approvalId());
        decide(g1.token(this), o.approvalId(), true);
        assertThat(status(bt, o.id())).isEqualTo("APPROVED");
    }

    @Test void rejectReturnsToDraftAndCanResubmit() throws Exception {
        String bt = buyer.token(this);
        Order o = submitOrder(bt, "1", "1");
        decide(m1.token(this), o.approvalId(), true);
        assertThat(decide(f2.token(this), o.approvalId(), false).path("data").path("status").asText()).isEqualTo("REJECTED");
        assertThat(status(bt, o.id())).isEqualTo("DRAFT");
        assertThat(decide(f1.token(this), o.approvalId(), true).path("code").asInt()).as("已结束").isEqualTo(400);
        long ap2 = ok(bt, "POST", "/api/purchase/orders/" + o.id() + "/submit", null).path("approvalId").asLong();
        assertThat(ap2).isNotEqualTo(o.approvalId());
        assertThat(ok(bt, "GET", "/api/workflow/approvals?bizId=" + o.id(), null).size()).isGreaterThanOrEqualTo(2);
        decide(m1.token(this), ap2, false);
        assertThat(status(bt, o.id())).isEqualTo("DRAFT");
    }

    @Test void countersignListFrozenAtStart() throws Exception {
        String bt = buyer.token(this);
        Order o = submitOrder(bt, "1", "1");
        decide(m1.token(this), o.approvalId(), true);
        // 提交后再加入财务角色的人不在会签名单里
        User late = user(fin);
        assertThat(decide(late.token(this), o.approvalId(), true).path("code").asInt()).isEqualTo(403);
        assertThat(todo(late.token(this))).doesNotContain(o.approvalId());
        decide(f1.token(this), o.approvalId(), true);
        assertThat(status(bt, o.id())).as("仍需名单内的 f2").isEqualTo("PENDING");
        decide(f2.token(this), o.approvalId(), true);
        assertThat(status(bt, o.id())).isEqualTo("APPROVED");
    }

    @Test void applicantCannotApproveOwnOrderEvenWithRole() throws Exception {
        Role mixed = role("MIX", "purchase:write");
        User self = user(mixed, mgr);                      // 既是申请人又有经理角色
        Order o = submitOrder(self.token(this), "1", "1");
        assertThat(decide(self.token(this), o.approvalId(), true).path("code").asInt()).isEqualTo(403);
        assertThat(todo(self.token(this))).doesNotContain(o.approvalId());
        assertThat(decide(m1.token(this), o.approvalId(), true).path("code").asInt()).isZero();
        decide(f1.token(this), o.approvalId(), false);   // 收尾，避免遗留审批中实例
        // 申请人不进会签名单：只有申请人自己具备某节点角色时提交被拒
        Role solo = role("SOLO");
        User only = user(mixed, solo);
        ok(admin, "PUT", "/api/workflow/definitions/PURCHASE_ORDER", "{\"name\":\"x\",\"nodes\":[{\"name\":\"唯一\",\"roleCode\":\"" + solo.code() + "\",\"approveMode\":\"ALL\"}]}");
        long id = ok(only.token(this), "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":1,\"price\":1}]}").path("order").path("id").asLong();
        JsonNode r = call(only.token(this), "POST", "/api/purchase/orders/" + id + "/submit", null);
        assertThat(r.path("code").asInt()).isEqualTo(400);
        assertThat(r.path("msg").asText()).contains("没有可用审批人");
    }

    @Test void concurrentDuplicateDecisionRecordedOnce() throws Exception {
        String bt = buyer.token(this);
        for (int round = 0; round < 3; round++) {
            Order o = submitOrder(bt, "1", "1");
            decide(m1.token(this), o.approvalId(), true);
            String ft = f1.token(this);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
            List<Future<Integer>> fs = new ArrayList<>();
            try {
                for (int k = 0; k < 2; k++)
                    fs.add(pool.submit(() -> { ready.countDown(); go.await(); return decide(ft, o.approvalId(), true).path("code").asInt(); }));
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                List<Integer> codes = new ArrayList<>();
                for (Future<Integer> f : fs) codes.add(f.get(30, TimeUnit.SECONDS));
                assertThat(codes).containsOnlyOnce(0).allMatch(c -> c == 0 || c == 400 || c == 40900);
            } finally {
                pool.shutdownNow();
            }
            JsonNode d = ok(bt, "GET", "/api/workflow/approvals/" + o.approvalId(), null);
            long f1Records = 0;
            for (JsonNode r : d.path("records")) if (r.path("approver").asText().equals(f1.username())) f1Records++;
            assertThat(f1Records).isEqualTo(1);
            assertThat(d.path("instance").path("status").asText()).isEqualTo("PENDING");
            decide(f2.token(this), o.approvalId(), false);
        }
    }

    @Test void definitionAdmin() throws Exception {
        JsonNode def = ok(admin, "GET", "/api/workflow/definitions/PURCHASE_ORDER", null);
        assertThat(def.path("nodes").size()).isEqualTo(3);
        assertThat(new java.math.BigDecimal(def.path("nodes").get(2).path("minAmount").asText())).isEqualByComparingTo("1000");
        assertThat(ok(admin, "GET", "/api/workflow/definitions", null).size()).isPositive();
        assertThat(code(buyer.token(this), "GET", "/api/workflow/definitions", null)).isEqualTo(403);
        assertThat(code(admin, "PUT", "/api/workflow/definitions/X", "{\"name\":\"x\",\"nodes\":[{\"name\":\"n\",\"roleCode\":\"R\",\"approveMode\":\"BAD\"}]}")).isEqualTo(400);
        assertThat(code(admin, "PUT", "/api/workflow/definitions/X", "{\"name\":\"x\",\"nodes\":[]}")).isEqualTo(400);

        Order o = submitOrder(buyer.token(this), "1", "1");
        assertThat(ok(admin, "GET", "/api/workflow/approvals/pending", null).size()).isPositive();
        assertThat(code(admin, "DELETE", "/api/workflow/definitions/PURCHASE_ORDER", null)).as("有审批中实例").isEqualTo(400);
        decide(m1.token(this), o.approvalId(), false);

        // 所有节点都带金额条件且不满足 → 提交被拒
        ok(admin, "PUT", "/api/workflow/definitions/PURCHASE_ORDER", "{\"name\":\"大额\",\"nodes\":[{\"name\":\"GM\",\"roleCode\":\"" + gm.code() + "\",\"approveMode\":\"ANY\",\"minAmount\":100000}]}");
        assertThat(code(buyer.token(this), "POST", "/api/purchase/orders/" + o.id() + "/submit", null)).isEqualTo(400);
        // 停用流程 → 回退默认单级审批（workflow:approve）
        ok(admin, "PUT", "/api/workflow/definitions/PURCHASE_ORDER", "{\"name\":\"停用\",\"enabled\":false,\"nodes\":[{\"name\":\"GM\",\"roleCode\":\"" + gm.code() + "\",\"approveMode\":\"ANY\"}]}");
        long ap = ok(buyer.token(this), "POST", "/api/purchase/orders/" + o.id() + "/submit", null).path("approvalId").asLong();
        assertThat(decide(admin, ap, true).path("data").path("status").asText()).isEqualTo("APPROVED");
        ok(admin, "DELETE", "/api/workflow/definitions/PURCHASE_ORDER", null);
        assertThat(code(admin, "GET", "/api/workflow/definitions/PURCHASE_ORDER", null)).isEqualTo(404);
        assertThat(code(admin, "DELETE", "/api/workflow/definitions/PURCHASE_ORDER", null)).isEqualTo(404);
        // 流程删除后，历史实例仍能看到当时的节点
        JsonNode hist = ok(admin, "GET", "/api/workflow/approvals/" + o.approvalId(), null);
        assertThat(hist.path("plan").size()).isEqualTo(2);
        assertThat(code(admin, "GET", "/api/workflow/approvals/999999", null)).isEqualTo(404);
        assertThat(decide(admin, 999999, true).path("code").asInt()).isEqualTo(404);
    }
}
