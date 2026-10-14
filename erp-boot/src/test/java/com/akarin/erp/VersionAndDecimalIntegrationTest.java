package com.akarin.erp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** A 乐观锁 version；B BigDecimal 序列化为字符串；C 行金额上限；D 中文默认提示 */
@SpringBootTest
@AutoConfigureMockMvc
class VersionAndDecimalIntegrationTest extends SysTestSupport {

    // ---------- A ----------
    @Test void masterDataUpdateRequiresVersionAndDetectsConflict() throws Exception {
        for (String path : new String[]{"materials", "customers", "suppliers", "warehouses"}) {
            long id = mdm(admin, path, "V");
            String url = "/api/mdm/" + path + "/" + id;
            JsonNode detail = ok(admin, "GET", url, null);
            assertThat(detail.path("version").asInt()).as(path + " 详情返回 version").isZero();
            JsonNode list = ok(admin, "GET", "/api/mdm/" + path + "?keyword=" + detail.path("code").asText(), null).path("records").get(0);
            assertThat(list.has("version")).as(path + " 列表返回 version").isTrue();
            // 不传 version → 400
            JsonNode noVer = call(admin, "PUT", url, "{\"name\":\"x\"}");
            assertThat(noVer.path("code").asInt()).isEqualTo(400);
            assertThat(noVer.path("msg").asText()).startsWith("version 不能为空");
            // 两个会话拿同一个 version：先提交的成功，后提交的 40900
            String s1 = login("admin", "admin123"), s2 = login("admin", "admin123");
            int v = detail.path("version").asInt();
            JsonNode first = call(s1, "PUT", url, "{\"name\":\"会话一\",\"version\":" + v + "}");
            assertThat(first.path("code").asInt()).isZero();
            assertThat(first.path("data").path("version").asInt()).isEqualTo(v + 1);
            JsonNode second = call(s2, "PUT", url, "{\"name\":\"会话二\",\"version\":" + v + "}");
            assertThat(second.path("code").asInt()).as(path).isEqualTo(40900);
            assertThat(second.path("msg").asText()).isEqualTo("数据已被修改，请刷新后重试");
            assertThat(ok(admin, "GET", url, null).path("name").asText()).as("后提交的没有覆盖").isEqualTo("会话一");
        }
    }

    @Test void userRoleAndPermissionUpdateUseVersionToo() throws Exception {
        User u = user();
        Role r = role("VR");
        long pid = ok(admin, "POST", "/api/sys/permissions", "{\"code\":\"" + uniq("v:p") + "\",\"name\":\"x\"}").path("id").asLong();
        String[] urls = {"/api/sys/users/" + u.id(), "/api/sys/roles/" + r.id(), "/api/sys/permissions/" + pid};
        String[] bodies = {"{\"realName\":\"%s\"%s}", "{\"name\":\"%s\"%s}", "{\"name\":\"%s\"%s}"};
        for (int i = 0; i < urls.length; i++) {
            long v = ver(admin, urls[i]);
            assertThat(code(admin, "PUT", urls[i], String.format(bodies[i], "a", ""))).as(urls[i] + " 不传 version").isEqualTo(400);
            assertThat(code(admin, "PUT", urls[i], String.format(bodies[i], "b", ",\"version\":" + v))).as(urls[i]).isZero();
            assertThat(code(admin, "PUT", urls[i], String.format(bodies[i], "c", ",\"version\":" + v))).as(urls[i] + " 旧 version").isEqualTo(40900);
        }
        assertThat(ok(admin, "GET", "/api/sys/users/" + u.id(), null).path("realName").asText()).isEqualTo("b");
    }

    @Test void disableAndResetPasswordTakeEffectEvenAfterRenameChangedVersion() throws Exception {
        User u = user();
        String url = "/api/sys/users/" + u.id();
        String oldToken = u.token(this);
        // 别人先改了姓名 → version 变了
        long v = ver(admin, url);
        ok(admin, "PUT", url, "{\"realName\":\"改名\",\"version\":" + v + "}");
        // 改密（不带 version）仍然生效：新密码能登录，旧 token 失效
        ok(admin, "PUT", url + "/password", "{\"password\":\"newpass123\"}");
        assertThat(code(null, "POST", "/api/auth/login", "{\"username\":\"" + u.username() + "\",\"password\":\"newpass123\"}")).isZero();
        assertThat(code(null, "POST", "/api/auth/login", "{\"username\":\"" + u.username() + "\",\"password\":\"" + PW + "\"}")).isEqualTo(401);
        assertThat(code(oldToken, "GET", "/api/auth/me", null)).isEqualTo(401);
        // 再改一次姓名，然后停用：停用真正生效，登录失败
        ok(admin, "PUT", url, "{\"realName\":\"再改名\",\"version\":" + ver(admin, url) + "}");
        JsonNode dis = ok(admin, "PUT", url + "/status", "{\"enabled\":false}");
        assertThat(dis.path("enabled").asBoolean()).isFalse();
        assertThat(ok(admin, "GET", url, null).path("enabled").asBoolean()).isFalse();
        assertThat(code(null, "POST", "/api/auth/login", "{\"username\":\"" + u.username() + "\",\"password\":\"newpass123\"}")).isEqualTo(403);
        // 姓名没被这些安全操作覆盖；安全操作不校验 version 但会递增 version
        assertThat(ok(admin, "GET", url, null).path("realName").asText()).isEqualTo("再改名");
        assertThat(ver(admin, url)).as("改名×2 + 改密 + 停用，每次都递增 version").isEqualTo(v + 4);
        // 重新启用后可以登录
        ok(admin, "PUT", url + "/status", "{\"enabled\":true}");
        assertThat(code(null, "POST", "/api/auth/login", "{\"username\":\"" + u.username() + "\",\"password\":\"newpass123\"}")).isZero();
    }

    @Test void masterDataUpdateChecksExistenceBeforeVersion() throws Exception {
        JsonNode r = call(admin, "PUT", "/api/mdm/materials/999999", "{\"name\":\"x\"}");
        assertThat(r.path("code").asInt()).as("不存在优先返回 404，而不是缺 version 的 400").isEqualTo(404);
        assertThat(code(admin, "PUT", "/api/sys/users/999999", "{\"realName\":\"x\"}")).isEqualTo(404);
    }

    @Test void softDeleteAfterRenameStillWorks() throws Exception {
        long id = mdm(admin, "materials", "D");
        String url = "/api/mdm/materials/" + id;
        ok(admin, "PUT", url, "{\"name\":\"改\",\"version\":" + ver(admin, url) + "}");
        ok(admin, "DELETE", url, null);
        assertThat(code(admin, "GET", url, null)).isEqualTo(404);
        Role r = role("DR");
        ok(admin, "PUT", "/api/sys/roles/" + r.id(), "{\"name\":\"改\",\"version\":" + ver(admin, "/api/sys/roles/" + r.id()) + "}");
        ok(admin, "DELETE", "/api/sys/roles/" + r.id(), null);
        assertThat(code(admin, "GET", "/api/sys/roles/" + r.id(), null)).isEqualTo(404);
    }

    // ---------- B ----------
    @Test void bigDecimalIsStringAndRoundTripsExactly() throws Exception {
        long sup = mdm(admin, "suppliers", "S");
        // 请求体用字符串
        JsonNode p = ok(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":\"1234567890123456.12\"}");
        assertThat(p.path("amount").isTextual()).as("金额序列化为字符串").isTrue();
        assertThat(p.path("amount").asText()).isEqualTo("1234567890123456.12");
        assertThat(p.path("appliedAmount").asText()).isEqualTo("0.00");
        // 再读出来完全一致
        JsonNode rec = ok(admin, "GET", "/api/finance/payments?partnerId=" + sup, null).path("records").get(0);
        assertThat(rec.path("amount").isTextual()).isTrue();
        assertThat(rec.path("amount").asText()).isEqualTo("1234567890123456.12");
        // 请求体用数字也能通过
        JsonNode n = ok(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":1234567890123456.12}");
        assertThat(n.path("amount").asText()).isEqualTo("1234567890123456.12");
        // 订单的数量 / 单价 / 行金额 / 合计都是字符串
        long wh = mdm(admin, "warehouses", "W"), mat = mdm(admin, "materials", "M");
        JsonNode o = ok(admin, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"3\",\"price\":1.2345}]}");
        assertThat(o.path("order").path("totalAmount").isTextual()).isTrue();
        JsonNode item = o.path("items").get(0);
        assertThat(item.path("price").asText()).as("单价 4 位小数原样保存").isEqualTo("1.2345");
        assertThat(item.path("qty").isTextual()).isTrue();
        assertThat(item.path("amount").asText()).as("3 × 1.2345 = 3.7035 → 3.70").isEqualTo("3.70");
        assertThat(o.path("order").path("totalAmount").asText()).isEqualTo("3.70");
    }

    @Test void illegalNumberStringIs400WithFieldName() throws Exception {
        long sup = mdm(admin, "suppliers", "S"), wh = mdm(admin, "warehouses", "W"), mat = mdm(admin, "materials", "M");
        JsonNode r = call(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + ",\"amount\":\"abc\"}");
        assertThat(r.path("code").asInt()).isEqualTo(400);
        assertThat(r.path("msg").asText()).isEqualTo("amount 必须是数字");
        JsonNode r2 = call(admin, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":1,\"price\":\"1.2.3\"}]}");
        assertThat(r2.path("msg").asText()).isEqualTo("items[0].price 必须是数字");
        assertThat(call(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":\"x\",\"amount\":1}").path("msg").asText()).isEqualTo("partnerId 必须是数字");
        assertThat(call(admin, "POST", "/api/finance/payments", "not-json").path("msg").asText()).isEqualTo("请求格式错误");
    }

    // ---------- C ----------
    @Test void lineAmountOverflowIs400NotServerError() throws Exception {
        long sup = mdm(admin, "suppliers", "S"), cus = mdm(admin, "customers", "C"), wh = mdm(admin, "warehouses", "W"), mat = mdm(admin, "materials", "M");
        for (String[] t : new String[][]{{"purchase", "supplierId", String.valueOf(sup)}, {"sales", "customerId", String.valueOf(cus)}}) {
            JsonNode r = call(admin, "POST", "/api/" + t[0] + "/orders", "{\"" + t[1] + "\":" + t[2] + ",\"warehouseId\":" + wh +
                    ",\"items\":[{\"materialId\":" + mat + ",\"qty\":99999999999999,\"price\":99999999999999}]}");
            assertThat(r.path("code").asInt()).as(t[0]).isEqualTo(400);
            assertThat(r.path("msg").asText()).isEqualTo("第 1 行金额整数部分最多 16 位，超出金额上限");
            // 每行都不超，但合计超
            JsonNode sum = call(admin, "POST", "/api/" + t[0] + "/orders", "{\"" + t[1] + "\":" + t[2] + ",\"warehouseId\":" + wh +
                    ",\"items\":[{\"materialId\":" + mat + ",\"qty\":100,\"price\":90000000000000},{\"materialId\":" + mat + ",\"qty\":100,\"price\":90000000000000}]}");
            assertThat(sum.path("msg").asText()).as(t[0]).isEqualTo("订单合计金额整数部分最多 16 位，超出金额上限");
        }
        // 入库后库存超过数量列上限
        ok(admin, "POST", "/api/inventory/in", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":99999999999999}");
        JsonNode over = call(admin, "POST", "/api/inventory/in", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":1}");
        assertThat(over.path("code").asInt()).isEqualTo(400);
        assertThat(over.path("msg").asText()).isEqualTo("入库后库存数量整数部分最多 14 位，超出数量上限");
    }

    @Test void salesWithFourDecimalPriceShipsAndOutboundQtyKeepsFourDecimals() throws Exception {
        String apr = login("approver", "approver123");
        long cus = mdm(admin, "customers", "C"), wh = mdm(admin, "warehouses", "W"), mat = mdm(admin, "materials", "M");
        ok(admin, "POST", "/api/inventory/in", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"10.5\"}");
        JsonNode o = ok(admin, "POST", "/api/sales/orders", "{\"customerId\":" + cus + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"2.5\",\"price\":\"3.3333\"}]}");
        long id = o.path("order").path("id").asLong();
        assertThat(o.path("items").get(0).path("price").asText()).isEqualTo("3.3333");
        assertThat(o.path("items").get(0).path("amount").asText()).as("2.5 × 3.3333 = 8.33325 → 8.33").isEqualTo("8.33");
        long ap = ok(admin, "POST", "/api/sales/orders/" + id + "/submit", null).path("approvalId").asLong();
        ok(apr, "POST", "/api/workflow/approvals/" + ap + "/decide", "{\"approved\":true}");
        ok(admin, "POST", "/api/sales/orders/" + id + "/ship", null);   // 出库
        JsonNode bill = ok(admin, "GET", "/api/finance/bills?sourceBizNo=" + o.path("order").path("orderNo").asText(), null).path("records").get(0);
        assertThat(bill.path("amount").asText()).isEqualTo("8.33");
        // 手工出库 4 位小数数量，库存余额精确
        JsonNode out = ok(admin, "POST", "/api/inventory/out", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"1.2345\"}");
        assertThat(out.toString()).doesNotContain("E+");
        JsonNode stock = ok(admin, "GET", "/api/inventory/stocks?materialId=" + mat + "&warehouseId=" + wh, null);
        assertThat(stock.toString()).contains("\"6.7655\"");   // 10.5 - 2.5 - 1.2345
    }

    // ---------- D ----------
    @Test void defaultValidationMessagesAreChineseWithFieldName() throws Exception {
        long sup = mdm(admin, "suppliers", "S");
        assertThat(call(admin, "POST", "/api/finance/payments", "{\"partnerId\":" + sup + ",\"amount\":1}").path("msg").asText()).isEqualTo("paymentType 不能为空");
        assertThat(call(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"amount\":1}").path("msg").asText()).isEqualTo("partnerId 不能为空");
        assertThat(call(admin, "POST", "/api/mdm/materials", "{\"code\":\"" + uniq("M") + "\"}").path("msg").asText()).isEqualTo("name 不能为空");
        assertThat(call(admin, "POST", "/api/mdm/materials", "{\"code\":\"" + uniq("M") + "\",\"name\":\"x\",\"status\":5}").path("msg").asText()).isEqualTo("status 不能大于 1");
        // FullMessage / @MoneyScale：金额与数量提示原样返回
        assertThat(call(admin, "POST", "/api/finance/payments", "{\"paymentType\":\"PAYMENT\",\"partnerId\":" + sup + "}").path("msg").asText()).isEqualTo("收付款金额不能为空");
        assertThat(call(admin, "POST", "/api/inventory/in", "{\"materialId\":1,\"warehouseId\":1}").path("msg").asText()).isEqualTo("数量不能为空");
        assertThat(call(admin, "POST", "/api/inventory/in", "{\"materialId\":1,\"warehouseId\":1,\"qty\":1.00001}").path("msg").asText()).isEqualTo("数量最多 4 位小数");
        assertThat(call(admin, "PUT", "/api/workflow/definitions/SALES_ORDER", "{\"name\":\"x\",\"enabled\":false,\"nodes\":[{\"name\":\"n\",\"roleCode\":\"ADMIN\",\"approveMode\":\"BOTH\"}]}")
                .path("msg").asText()).isEqualTo("approveMode 只能是 ALL（会签）或 ANY（或签）");
    }
}
