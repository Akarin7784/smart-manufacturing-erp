package com.akarin.erp;

import com.akarin.erp.finance.entity.ArapBill;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** 主数据：编码不可改、逻辑删除后编码不可复用、被引用不能删（40901）只能停用、请求校验 */
@SpringBootTest
@AutoConfigureMockMvc
class MdmRulesIntegrationTest extends SysTestSupport {
    @Autowired ArapBillMapper billMapper;
    @Autowired JdbcTemplate jdbc;

    @Test void codeImmutableAndValidation() throws Exception {
        String code = uniq("M");
        long id = ok(admin, "POST", "/api/mdm/materials", "{\"code\":\"" + code + "\",\"name\":\"螺丝\"}").path("id").asLong();
        JsonNode r = call(admin, "PUT", "/api/mdm/materials/" + id, "{\"code\":\"OTHER\",\"name\":\"螺丝\"}");
        assertThat(r.path("code").asInt()).isEqualTo(400);
        assertThat(r.path("msg").asText()).contains("编码不可修改");
        assertThat(ok(admin, "PUT", "/api/mdm/materials/" + id, "{\"code\":\"" + code + "\",\"name\":\"新螺丝\",\"spec\":\"M6\"}").path("name").asText()).isEqualTo("新螺丝");

        assertThat(code(admin, "POST", "/api/mdm/materials", "{\"code\":\"\",\"name\":\"x\"}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/mdm/customers", "{\"code\":\"" + "C".repeat(65) + "\",\"name\":\"x\"}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/mdm/suppliers", "{\"code\":\"" + uniq("S") + "\",\"name\":\"" + "n".repeat(129) + "\"}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/mdm/warehouses", "{\"code\":\"" + uniq("W") + "\"}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/mdm/materials", "{\"code\":\"" + uniq("M") + "\",\"name\":\"x\",\"status\":2}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/mdm/warehouses", "{\"code\":\"" + "W".repeat(64) + "\",\"name\":\"" + "n".repeat(128) + "\"}")).as("边界值通过").isZero();
    }

    @Test void logicalDeleteAndCodeNotReusable() throws Exception {
        for (String path : new String[]{"materials", "customers", "suppliers", "warehouses"}) {
            String code = uniq("D");
            String body = "{\"code\":\"" + code + "\",\"name\":\"x\"}";
            long id = ok(admin, "POST", "/api/mdm/" + path, body).path("id").asLong();
            ok(admin, "DELETE", "/api/mdm/" + path + "/" + id, null);
            assertThat(code(admin, "GET", "/api/mdm/" + path + "/" + id, null)).isEqualTo(404);
            JsonNode dup = call(admin, "POST", "/api/mdm/" + path, body);
            assertThat(dup.path("code").asInt()).as(path).isEqualTo(40001);
            assertThat(dup.path("msg").asText()).startsWith("编码已存在（含已删除）");
        }
        assertThat(code(admin, "DELETE", "/api/mdm/materials/999999", null)).isEqualTo(404);
    }

    @Test void referencedMasterDataCannotBeDeletedOnlyDisabled() throws Exception {
        long sup = mdm(admin, "suppliers", "S"), cus = mdm(admin, "customers", "C"), wh = mdm(admin, "warehouses", "W"), mat = mdm(admin, "materials", "M");
        ok(admin, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh + ",\"items\":[{\"materialId\":" + mat + ",\"qty\":1,\"price\":1}]}");
        ok(admin, "POST", "/api/sales/orders", "{\"customerId\":" + cus + ",\"warehouseId\":" + wh + ",\"items\":[{\"materialId\":" + mat + ",\"qty\":1,\"price\":1}]}");
        for (String url : new String[]{"/api/mdm/suppliers/" + sup, "/api/mdm/customers/" + cus, "/api/mdm/warehouses/" + wh, "/api/mdm/materials/" + mat}) {
            JsonNode r = call(admin, "DELETE", url, null);
            assertThat(r.path("code").asInt()).as(url).isEqualTo(40901);
            assertThat(r.path("msg").asText()).contains("订单").contains("只能停用");
        }
        // 只被库存流水引用的物料/仓库
        long wh2 = mdm(admin, "warehouses", "W"), mat2 = mdm(admin, "materials", "M");
        ok(admin, "POST", "/api/inventory/in", "{\"materialId\":" + mat2 + ",\"warehouseId\":" + wh2 + ",\"qty\":3}");
        assertThat(call(admin, "DELETE", "/api/mdm/materials/" + mat2, null).path("msg").asText()).contains("库存流水");
        assertThat(call(admin, "DELETE", "/api/mdm/warehouses/" + wh2, null).path("code").asInt()).isEqualTo(40901);
        // 只被应收应付单引用的客户/供应商
        long cus2 = mdm(admin, "customers", "C"), sup2 = mdm(admin, "suppliers", "S");
        bill("AR", cus2); bill("AP", sup2);
        assertThat(call(admin, "DELETE", "/api/mdm/customers/" + cus2, null).path("msg").asText()).contains("应收应付单");
        assertThat(call(admin, "DELETE", "/api/mdm/suppliers/" + sup2, null).path("code").asInt()).isEqualTo(40901);

        // 停用可以
        JsonNode m = ok(admin, "GET", "/api/mdm/materials/" + mat, null);
        ok(admin, "PUT", "/api/mdm/materials/" + mat, "{\"code\":\"" + m.path("code").asText() + "\",\"name\":\"x\",\"status\":0}");
        assertThat(ok(admin, "GET", "/api/mdm/materials/" + mat, null).path("status").asInt()).isZero();
        assertThat(ok(admin, "GET", "/api/mdm/materials?status=0&keyword=" + m.path("code").asText(), null).path("total").asInt()).isEqualTo(1);
        // 未被引用的可直接删
        long free = mdm(admin, "warehouses", "W");
        ok(admin, "DELETE", "/api/mdm/warehouses/" + free, null);
    }

    @Test void partnerReferencedByPaymentCannotBeDeleted() throws Exception {
        long cus = mdm(admin, "customers", "C"), sup = mdm(admin, "suppliers", "S");
        ok(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"RECEIPT\",\"partnerId\":" + cus + ",\"amount\":10}");
        ok(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":10}");
        for (String url : new String[]{"/api/mdm/customers/" + cus, "/api/mdm/suppliers/" + sup}) {
            JsonNode r = call(admin, "DELETE", url, null);
            assertThat(r.path("code").asInt()).as(url).isEqualTo(40901);
            assertThat(r.path("msg").asText()).as(url).contains("收付款");
        }
    }

    @Test void recreateSoftDeletedCodeReturns40001() throws Exception {
        long id = ok(admin, "GET", "/api/mdm/materials?keyword=DM-M001", null).path("records").get(0).path("id").asLong();
        try {
            ok(admin, "DELETE", "/api/mdm/materials/" + id, null);
            JsonNode r = call(admin, "POST", "/api/mdm/materials", "{\"code\":\"DM-M001\",\"name\":\"again\"}");
            assertThat(r.path("code").asInt()).isEqualTo(40001);
            assertThat(r.path("msg").asText()).startsWith("编码已存在");
        } finally {
            jdbc.update("UPDATE mdm_material SET deleted = 0 WHERE id = ?", id);   // 还原演示数据，其他用例依赖它
        }
        // 用户名、角色编码、权限点编码：删除后再建同名 → 40001
        String un = uniq("u"), rc = uniq("R").toUpperCase(), pc = uniq("p:x");
        long uid = ok(admin, "POST", "/api/sys/users", "{\"username\":\"" + un + "\",\"password\":\"" + PW + "\"}").path("id").asLong();
        long rid = ok(admin, "POST", "/api/sys/roles", "{\"code\":\"" + rc + "\",\"name\":\"x\"}").path("id").asLong();
        long pid = ok(admin, "POST", "/api/sys/permissions", "{\"code\":\"" + pc + "\",\"name\":\"x\"}").path("id").asLong();
        ok(admin, "DELETE", "/api/sys/users/" + uid, null);
        ok(admin, "DELETE", "/api/sys/roles/" + rid, null);
        ok(admin, "DELETE", "/api/sys/permissions/" + pid, null);
        for (String[] c : new String[][]{{"/api/sys/users", "{\"username\":\"" + un + "\",\"password\":\"" + PW + "\"}"},
                {"/api/sys/roles", "{\"code\":\"" + rc + "\",\"name\":\"x\"}"}, {"/api/sys/permissions", "{\"code\":\"" + pc + "\",\"name\":\"x\"}"}}) {
            JsonNode r = call(admin, "POST", c[0], c[1]);
            assertThat(r.path("code").asInt()).as(c[0]).isEqualTo(40001);
            assertThat(r.path("msg").asText()).as(c[0]).startsWith("编码已存在");
        }
    }

    @Test void concurrentSameCodeCreationReturns40001() throws Exception {
        String[][] targets = {
                {"/api/mdm/materials", "{\"code\":\"%s\",\"name\":\"x\"}"}, {"/api/mdm/customers", "{\"code\":\"%s\",\"name\":\"x\"}"},
                {"/api/mdm/suppliers", "{\"code\":\"%s\",\"name\":\"x\"}"}, {"/api/mdm/warehouses", "{\"code\":\"%s\",\"name\":\"x\"}"},
                {"/api/sys/users", "{\"username\":\"%s\",\"password\":\"" + PW + "\"}"},
                {"/api/sys/roles", "{\"code\":\"%s\",\"name\":\"x\"}"}, {"/api/sys/permissions", "{\"code\":\"%s\",\"name\":\"x\"}"}};
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (String[] tg : targets) for (int round = 0; round < 3; round++) {
                String body = String.format(tg[1], uniq("CC").toUpperCase());
                CyclicBarrier go = new CyclicBarrier(4);
                List<Future<Integer>> fs = new ArrayList<>();
                for (int k = 0; k < 4; k++) fs.add(pool.submit(() -> { go.await(); return call(admin, "POST", tg[0], body).path("code").asInt(); }));
                List<Integer> codes = new ArrayList<>();
                for (Future<Integer> f : fs) codes.add(f.get(30, TimeUnit.SECONDS));
                assertThat(codes).as(tg[0] + " " + codes).containsOnlyOnce(0).allMatch(c -> c == 0 || c == 40001);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    void bill(String type, long partner) {
        ArapBill b = new ArapBill();
        b.setBillNo(uniq(type)); b.setBillType(type); b.setPartnerId(partner);
        b.setAmount(BigDecimal.ONE); b.setSettledAmount(BigDecimal.ZERO); b.setStatus("OPEN");
        billMapper.insert(b);
    }
}
