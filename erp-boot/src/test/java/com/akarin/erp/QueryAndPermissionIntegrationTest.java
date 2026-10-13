package com.akarin.erp;

import com.akarin.erp.auth.entity.SysUser;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** 查询参数分支、订单驳回/重提、非财务权限 403 */
@SpringBootTest
@AutoConfigureMockMvc
class QueryAndPermissionIntegrationTest extends ApiTestSupport {
    @Autowired SysUserMapper userMapper;
    @Autowired PasswordEncoder encoder;
    String admin;

    @BeforeEach void setUp() throws Exception { admin = login("admin", "admin123"); }

    @Test void financeQueriesWithAndWithoutFilters() throws Exception {
        for (String url : new String[]{"/api/finance/bills", "/api/finance/bills?billType=AP&partnerId=1&status=OPEN&sourceBizNo=X",
                "/api/finance/payments", "/api/finance/payments?paymentType=PAYMENT&partnerId=1",
                "/api/finance/settlements", "/api/finance/settlements?billId=1",
                "/api/finance/reconciliation", "/api/finance/reconciliation?billType=AR"}) {
            assertThat(ok(admin, "GET", url, null)).as(url).isNotNull();
        }
        assertThat(ok(admin, "GET", "/api/finance/bills?sourceBizNo=NOPE", null).path("total").asInt()).isZero();
    }

    @Test void unauthenticatedAndForbiddenReturnJsonBody() throws Exception {
        var r401 = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/finance/bills")).andReturn().getResponse();
        assertThat(r401.getStatus()).isEqualTo(401);
        assertThat(r401.getContentType()).startsWith("application/json");
        assertThat(om.readTree(r401.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(om.readTree("{\"code\":401,\"msg\":\"未登录或登录已失效\",\"data\":null}"));
        // 无效 token 同样 401 + JSON
        assertThat(call("not-a-jwt", "GET", "/api/finance/bills", null).path("msg").asText()).isEqualTo("未登录或登录已失效");

        SysUser u = new SysUser();
        u.setUsername(uniq("viewer")); u.setPassword(encoder.encode("pw123456")); u.setStatus(1);
        userMapper.insert(u);
        JsonNode r403 = call(login(u.getUsername(), "pw123456"), "GET", "/api/finance/bills", null);
        assertThat(r403).isEqualTo(om.readTree("{\"code\":403,\"msg\":\"无权限\",\"data\":null}"));
    }

    @Test void actuatorHealthIsPublicAndNotFoundIsJson404() throws Exception {
        var h = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health")).andReturn().getResponse();
        assertThat(h.getStatus()).isEqualTo(200);
        assertThat(om.readTree(h.getContentAsString()).path("status").asText()).isEqualTo("UP");
        // 只暴露 health
        assertThat(code(admin, "GET", "/actuator/env", null)).isIn(401, 403, 404);
        JsonNode nf = call(admin, "GET", "/api/definitely/not/here", null);   // call() 已断言 HTTP 状态 == body.code
        assertThat(nf).isEqualTo(om.readTree("{\"code\":404,\"msg\":\"资源不存在\",\"data\":null}"));
        assertThat(call(admin, "PUT", "/api/finance/reconciliation", null).path("code").asInt()).isEqualTo(405);
    }

    @Test void demoMasterDataSeeded() throws Exception {
        assertThat(ok(admin, "GET", "/api/mdm/materials?keyword=DM-M", null).path("total").asInt()).isEqualTo(5);
        assertThat(ok(admin, "GET", "/api/mdm/customers?keyword=DM-C", null).path("total").asInt()).isEqualTo(3);
        assertThat(ok(admin, "GET", "/api/mdm/suppliers?keyword=DM-S", null).path("total").asInt()).isEqualTo(3);
        assertThat(ok(admin, "GET", "/api/mdm/warehouses?keyword=DM-W", null).path("total").asInt()).isEqualTo(3);
        assertThat(code(admin, "POST", "/api/mdm/materials", "{\"code\":\"DM-M001\",\"name\":\"dup\"}")).isEqualTo(40001);
        assertThat(call(admin, "POST", "/api/mdm/materials", "{\"code\":\"DM-M001\",\"name\":\"dup\"}").path("msg").asText()).startsWith("编码已存在");
    }

    @Test void validationAndNotFound() throws Exception {
        assertThat(code(admin, "GET", "/api/finance/bills/999999", null)).isEqualTo(404);
        // 0.001 不满足 @DecimalMin(0.01)/@Digits(fraction=2)
        assertThat(code(admin, "POST", "/api/finance/settlements", "{\"requestNo\":\"v1\",\"paymentId\":1,\"billId\":1,\"amount\":0.001}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/finance/settlements", "{\"requestNo\":\"v2\",\"paymentId\":1,\"billId\":1,\"amount\":1.234}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":1,\"amount\":0.004}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/finance/payments", "not-json")).isEqualTo(400);
        assertThat(code(admin, "GET", "/api/finance/bills?partnerId=abc", null)).isEqualTo(400);
        assertThat(code(admin, "GET", "/api/no-such-endpoint", null)).isEqualTo(404);
        assertThat(code(admin, "DELETE", "/api/finance/bills", null)).isEqualTo(405);
    }

    @Test void orderQueriesRejectAndResubmit() throws Exception {
        for (String type : new String[]{"purchase", "sales"}) {
            String partner = type.equals("purchase") ? "supplier" : "customer";
            long p = mdm(admin, partner.equals("supplier") ? "suppliers" : "customers", "P"), wh = mdm(admin, "warehouses", "W"), m = mdm(admin, "materials", "M");
            JsonNode o = ok(admin, "POST", "/api/" + type + "/orders", "{\"" + partner + "Id\":" + p + ",\"warehouseId\":" + wh +
                    ",\"items\":[{\"materialId\":" + m + ",\"qty\":1,\"price\":1}]}").path("order");
            long id = o.path("id").asLong();
            long ap = ok(admin, "POST", "/api/" + type + "/orders/" + id + "/submit", null).path("approvalId").asLong();
            assertThat(code(admin, "POST", "/api/" + type + "/orders/" + id + "/submit", null)).isEqualTo(400);
            ok(login("approver", "approver123"), "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":false,\"comment\":\"no\"}");
            assertThat(ok(admin, "GET", "/api/" + type + "/orders/" + id, null).path("order").path("status").asText()).as("驳回后回到草稿，可修改重提（P2 行为）").isEqualTo("DRAFT");
            ok(admin, "POST", "/api/" + type + "/orders/" + id + "/submit", null);
            assertThat(ok(admin, "GET", "/api/" + type + "/orders?status=PENDING", null).path("total").asInt()).isPositive();
            assertThat(ok(admin, "GET", "/api/" + type + "/orders", null).path("total").asInt()).isPositive();
            assertThat(code(admin, "GET", "/api/" + type + "/orders/999999", null)).isEqualTo(400);
        }
    }

    @Test void userWithoutFinancePermissionGets403OnSettlement() throws Exception {
        SysUser u = new SysUser();
        u.setUsername(uniq("clerk")); u.setPassword(encoder.encode("pw123456")); u.setStatus(1);
        userMapper.insert(u);
        String t = login(u.getUsername(), "pw123456");
        assertThat(t).isNotBlank();
        String body = "{\"requestNo\":\"x\",\"paymentId\":1,\"billId\":1,\"amount\":1}";
        assertThat(code(t, "POST", "/api/finance/settlements", body)).isEqualTo(403);
        assertThat(code(t, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":1,\"amount\":1}")).isEqualTo(403);
        assertThat(code(t, "GET", "/api/finance/bills", null)).isEqualTo(403);
        assertThat(code(t, "GET", "/api/finance/reconciliation", null)).isEqualTo(403);
        assertThat(code(t, "GET", "/api/finance/settlements", null)).isEqualTo(403);
        assertThat(code(null, "GET", "/api/finance/bills", null)).isEqualTo(401);
    }
}
