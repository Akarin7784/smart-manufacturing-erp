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
            ok(admin, "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":false,\"comment\":\"no\"}");
            assertThat(ok(admin, "GET", "/api/" + type + "/orders/" + id, null).path("order").path("status").asText()).isEqualTo("REJECTED");
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
