package com.akarin.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** H2 全链路：订单 → 审批 → 出入库 → 应收应付 → 收付款 → 核销 → 对账 */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceFlowIntegrationTest {
    static final AtomicInteger SEQ = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    String token;

    @BeforeEach void login() throws Exception {
        token = call("POST", "/api/auth/login", "{\"username\":\"admin\",\"password\":\"admin123\"}", false).path("data").path("token").asText();
    }

    JsonNode call(String method, String url, String body, boolean auth) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(body);
        if (auth) req.header("Authorization", "Bearer " + token);
        return om.readTree(mvc.perform(req).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    JsonNode ok(String method, String url, String body) throws Exception {
        JsonNode r = call(method, url, body, true);
        assertThat(r.path("code").asInt()).as(url + " -> " + r).isZero();
        return r.path("data");
    }
    long create(String path, String prefix) throws Exception {
        String code = prefix + SEQ.incrementAndGet() + UUID.randomUUID().toString().substring(0, 6);
        return ok("POST", "/api/mdm/" + path, "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}").path("id").asLong();
    }
    long approveOrder(String type, long orderId) throws Exception {
        long approvalId = ok("POST", "/api/" + type + "/orders/" + orderId + "/submit", null).path("approvalId").asLong();
        ok("POST", "/api/workflow/approvals/" + approvalId + "/decide", "{\"approved\":true}");
        return approvalId;
    }
    JsonNode billBySource(String orderNo) throws Exception {
        JsonNode recs = ok("GET", "/api/finance/bills?sourceBizNo=" + orderNo, null).path("records");
        assertThat(recs.size()).isEqualTo(1);
        return recs.get(0);
    }
    String settleBody(String req, long paymentId, long billId, String amt) {
        return "{\"requestNo\":\"" + req + "\",\"paymentId\":" + paymentId + ",\"billId\":" + billId + ",\"amount\":" + amt + "}";
    }

    @Test void purchaseReceiptToPayableToPaymentSettlement() throws Exception {
        long mat = create("materials", "M"), sup = create("suppliers", "S"), wh = create("warehouses", "W");
        JsonNode order = ok("POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":100,\"price\":0.5},{\"materialId\":" + mat + ",\"qty\":3,\"price\":0.335}]}").path("order");
        long orderId = order.path("id").asLong();
        String orderNo = order.path("orderNo").asText();
        assertThat(order.path("totalAmount").decimalValue()).isEqualByComparingTo("51.01");
        approveOrder("purchase", orderId);
        assertThat(ok("POST", "/api/purchase/orders/" + orderId + "/receive", null).path("order").path("status").asText()).isEqualTo("RECEIVED");
        // 重复收货被拒绝，不会生成第二张应付
        assertThat(call("POST", "/api/purchase/orders/" + orderId + "/receive", null, true).path("code").asInt()).isEqualTo(400);

        JsonNode bill = billBySource(orderNo);
        assertThat(bill.path("billType").asText()).isEqualTo("AP");
        assertThat(bill.path("partnerId").asLong()).isEqualTo(sup);
        assertThat(bill.path("amount").decimalValue()).isEqualByComparingTo("51.01");
        assertThat(bill.path("status").asText()).isEqualTo("OPEN");
        long billId = bill.path("id").asLong();

        long payId = ok("POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":60}").path("id").asLong();
        String r1 = UUID.randomUUID().toString();
        ok("POST", "/api/finance/settlements", settleBody(r1, payId, billId, "20"));
        assertThat(ok("GET", "/api/finance/bills/" + billId, null).path("status").asText()).isEqualTo("PARTIAL");
        // 幂等：同一请求号重放不重复核销
        ok("POST", "/api/finance/settlements", settleBody(r1, payId, billId, "20"));
        assertThat(ok("GET", "/api/finance/bills/" + billId, null).path("settledAmount").decimalValue()).isEqualByComparingTo("20");
        // 同一请求号换参数 → 409
        assertThat(call("POST", "/api/finance/settlements", settleBody(r1, payId, billId, "21"), true).path("code").asInt()).isEqualTo(409);
        // 超额核销被拒
        JsonNode over = call("POST", "/api/finance/settlements", settleBody(UUID.randomUUID().toString(), payId, billId, "31.02"), true);
        assertThat(over.path("code").asInt()).isEqualTo(400);
        ok("POST", "/api/finance/settlements", settleBody(UUID.randomUUID().toString(), payId, billId, "31.01"));
        JsonNode settled = ok("GET", "/api/finance/bills/" + billId, null);
        assertThat(settled.path("status").asText()).isEqualTo("SETTLED");
        assertThat(ok("GET", "/api/finance/settlements?billId=" + billId, null).path("total").asInt()).isEqualTo(2);

        JsonNode rec = ok("GET", "/api/finance/reconciliation?billType=AP&partnerId=" + sup, null).get(0);
        assertThat(rec.path("totalAmount").decimalValue()).isEqualByComparingTo("51.01");
        assertThat(rec.path("settledAmount").decimalValue()).isEqualByComparingTo("51.01");
        assertThat(rec.path("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(rec.path("billCount").asInt()).isEqualTo(1);
    }

    @Test void salesShipmentToReceivableToReceiptSettlement() throws Exception {
        long mat = create("materials", "M"), cus = create("customers", "C"), wh = create("warehouses", "W");
        ok("POST", "/api/inventory/in", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":10}");

        // 库存不足：发货失败，订单保持 APPROVED，不产生应收
        JsonNode big = ok("POST", "/api/sales/orders", "{\"customerId\":" + cus + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":11,\"price\":2}]}").path("order");
        approveOrder("sales", big.path("id").asLong());
        JsonNode fail = call("POST", "/api/sales/orders/" + big.path("id").asLong() + "/ship", null, true);
        assertThat(fail.path("code").asInt()).isEqualTo(400);
        assertThat(fail.path("msg").asText()).contains("库存不足");
        assertThat(ok("GET", "/api/sales/orders/" + big.path("id").asLong(), null).path("order").path("status").asText()).isEqualTo("APPROVED");
        assertThat(ok("GET", "/api/finance/bills?sourceBizNo=" + big.path("orderNo").asText(), null).path("total").asInt()).isZero();

        // 未审批不能发货
        JsonNode order = ok("POST", "/api/sales/orders", "{\"customerId\":" + cus + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":4,\"price\":12.5}]}").path("order");
        long orderId = order.path("id").asLong();
        assertThat(call("POST", "/api/sales/orders/" + orderId + "/ship", null, true).path("code").asInt()).isEqualTo(400);
        approveOrder("sales", orderId);
        assertThat(ok("POST", "/api/sales/orders/" + orderId + "/ship", null).path("order").path("status").asText()).isEqualTo("SHIPPED");
        JsonNode stock = ok("GET", "/api/inventory/stocks?materialId=" + mat + "&warehouseId=" + wh, null).path("records").get(0);
        assertThat(stock.path("qty").decimalValue()).isEqualByComparingTo("6");

        JsonNode bill = billBySource(order.path("orderNo").asText());
        assertThat(bill.path("billType").asText()).isEqualTo("AR");
        assertThat(bill.path("amount").decimalValue()).isEqualByComparingTo("50");
        long billId = bill.path("id").asLong();

        // 付款单不能核销应收
        long wrongPay = ok("POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + create("suppliers", "S") + ",\"amount\":50}").path("id").asLong();
        assertThat(call("POST", "/api/finance/settlements", settleBody(UUID.randomUUID().toString(), wrongPay, billId, "1"), true).path("code").asInt()).isEqualTo(400);

        long rc = ok("POST", "/api/finance/payments", "{\"paymentType\":\"RECEIPT\",\"partnerId\":" + cus + ",\"amount\":50}").path("id").asLong();
        ok("POST", "/api/finance/settlements", settleBody(UUID.randomUUID().toString(), rc, billId, "50"));
        assertThat(ok("GET", "/api/finance/bills/" + billId, null).path("status").asText()).isEqualTo("SETTLED");
        // 已核销单据再核销被拒
        assertThat(call("POST", "/api/finance/settlements", settleBody(UUID.randomUUID().toString(), rc, billId, "1"), true).path("code").asInt()).isEqualTo(400);

        JsonNode rec = ok("GET", "/api/finance/reconciliation?billType=AR&partnerId=" + cus, null).get(0);
        assertThat(rec.path("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(ok("GET", "/api/finance/payments?partnerId=" + cus, null).path("records").get(0).path("appliedAmount").decimalValue()).isEqualByComparingTo("50");
    }

    @Test void financeEndpointsRequireAuth() throws Exception {
        assertThat(mvc.perform(get("/api/finance/reconciliation")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
}
