package com.akarin.erp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 未配置流程时兼容原单级审批（workflow:approve），采购与销售 */
@SpringBootTest
@AutoConfigureMockMvc
class DefaultApprovalIntegrationTest extends SysTestSupport {

    @Test void purchaseAndSalesWithDefaultSingleApproval() throws Exception {
        String approver = user(role("APPR", "workflow:approve")).token(this);
        long sup = mdm(admin, "suppliers", "S"), cus = mdm(admin, "customers", "C"), wh = mdm(admin, "warehouses", "W"), mat = mdm(admin, "materials", "M");
        JsonNode po = ok(admin, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":10,\"price\":3}]}").path("order");
        long ap = ok(admin, "POST", "/api/purchase/orders/" + po.path("id").asLong() + "/submit", null).path("approvalId").asLong();
        assertThat(ok(approver, "GET", "/api/workflow/approvals/todo", null).findValuesAsText("id")).contains(String.valueOf(ap));
        assertThat(ok(admin, "GET", "/api/workflow/approvals/todo", null).findValuesAsText("id")).as("自己提交的不出现在待办").doesNotContain(String.valueOf(ap));
        JsonNode self = call(admin, "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":true}");
        assertThat(self.path("code").asInt()).as("申请人（即使是 admin）不能审批自己的单据").isEqualTo(403);
        assertThat(self.path("msg").asText()).contains("自己");
        User noPerm = user();
        assertThat(code(noPerm.token(this), "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":true}")).isEqualTo(403);
        ok(approver, "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":true}");
        ok(admin, "POST", "/api/purchase/orders/" + po.path("id").asLong() + "/receive", null);
        JsonNode d = ok(admin, "GET", "/api/workflow/approvals/" + ap, null);
        assertThat(d.path("plan").size()).isZero();
        assertThat(d.path("records").get(0).path("nodeName").asText()).isEqualTo("默认审批");

        JsonNode so = ok(admin, "POST", "/api/sales/orders", "{\"customerId\":" + cus + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":4,\"price\":5}]}").path("order");
        long soId = so.path("id").asLong();
        long sap = ok(admin, "POST", "/api/sales/orders/" + soId + "/submit", null).path("approvalId").asLong();
        assertThat(code(admin, "POST", "/api/sales/orders/" + soId + "/submit", null)).as("审批中不能重复提交").isEqualTo(400);
        ok(approver, "POST", "/api/workflow/approvals/" + sap + "/decide", "{\"approved\":false}");
        assertThat(ok(admin, "GET", "/api/sales/orders/" + soId, null).path("order").path("status").asText()).isEqualTo("DRAFT");
        long sap2 = ok(admin, "POST", "/api/sales/orders/" + soId + "/submit", null).path("approvalId").asLong();
        ok(approver, "POST", "/api/workflow/approvals/" + sap2 + "/decide", "{\"approved\":true}");
        assertThat(ok(admin, "POST", "/api/sales/orders/" + soId + "/ship", null).path("order").path("status").asText()).isEqualTo("SHIPPED");
        assertThat(ok(admin, "GET", "/api/inventory/stocks?materialId=" + mat + "&warehouseId=" + wh, null).path("records").get(0).path("qty").decimalValue()).isEqualByComparingTo("6");
        assertThat(code(admin, "POST", "/api/sales/orders/" + soId + "/ship", null)).isEqualTo(400);
    }
}
