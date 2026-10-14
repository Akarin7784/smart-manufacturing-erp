package com.akarin.erp;

import com.akarin.erp.purchase.mapper.PurchaseOrderMapper;
import com.akarin.erp.purchase.service.PurchaseOrderService;
import com.akarin.erp.sales.mapper.SalesOrderMapper;
import com.akarin.erp.sales.service.SalesOrderService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只在 MySQL（InnoDB，默认 REPEATABLE READ）上跑：mvn -Pmysql-it，CI 的 MySQL smoke job 调用。
 * 确定性交错：编辑事务里 selectById 读完订单（RR 快照在这一刻建立）之后、定向更新之前，
 * 钩子在另一个独立提交的事务里把订单提交掉（DRAFT → PENDING，version + 1）。
 * 断言：钩子触发了、编辑的定向更新影响 0 行、接口返回 409/40903（不是 40900）。
 */
@Tag("mysql")
@ActiveProfiles("mysql")
@SpringBootTest
@AutoConfigureMockMvc
class OrderEditSubmitRaceMySqlTest extends ApiTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired PurchaseOrderService purchaseOrders;
    @Autowired SalesOrderService salesOrders;

    static volatile String armedTable;
    static volatile long armedId;
    static volatile Runnable armedAction;
    static volatile int fired;
    /** 钩子触发之后，同一张表上下一次 update(...) 的影响行数；-1 表示没观察到 */
    static volatile int rowsAfterHook = -1;
    static volatile String hookedTable;
    static volatile TransactionTemplate hookTx;

    @TestConfiguration
    static class Hook {
        private static final Map<Class<?>, String> TABLES = Map.of(
                PurchaseOrderMapper.class, "pur_order", SalesOrderMapper.class, "sal_order");

        @Bean BeanPostProcessor orderRaceHook() {
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
                                hookTx.executeWithoutResult(st -> act.run());   // 独立事务，提交后才返回
                                fired++;
                                hookedTable = table;
                            } else if (m.getName().equals("update") && table.equals(hookedTable) && rowsAfterHook == -1) {
                                rowsAfterHook = (Integer) r;
                            }
                            return r;
                        });
                    }
                    return bean;
                }
            };
        }
    }

    String admin;
    long sup, cust, wh, mat;

    @BeforeEach void setUp() throws Exception {
        hookTx = new TransactionTemplate(txm);
        hookTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String pw = TestCredentials.ADMIN_PASSWORD;
        admin = login("admin", pw);
        assertThat(admin).as("admin 登录（密码取 ERP_ADMIN_PASSWORD）").isNotBlank();
        String iso = jdbc.queryForObject("SELECT @@transaction_isolation", String.class);
        assertThat(iso).as("这条用例要在 InnoDB 默认隔离级别下复现").isEqualTo("REPEATABLE-READ");
        sup = mdm(admin, "suppliers", "RS"); cust = mdm(admin, "customers", "RC");
        wh = mdm(admin, "warehouses", "RW"); mat = mdm(admin, "materials", "RM");
    }

    @AfterEach void reset() { armedTable = null; armedAction = null; fired = 0; rowsAfterHook = -1; hookedTable = null; }

    String body(int version, String partnerField, long partnerId) {
        return "{\"version\":" + version + ",\"" + partnerField + "\":" + partnerId + ",\"warehouseId\":" + wh +
                ",\"remark\":\"edit\",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"2\",\"price\":\"10\"}]}";
    }

    void race(String type, String table, String partnerField, long partnerId, Runnable submit) throws Exception {
        long id = ok(admin, "POST", "/api/" + type + "/orders",
                "{\"" + partnerField + "\":" + partnerId + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"1\",\"price\":\"10\"}]}").path("order").path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT version FROM " + table + " WHERE id=?", Integer.class, id)).isZero();
        armedId = id; lastArmed = id; armedAction = submit; armedTable = table;
        // 编辑和提交拿的是同一个 version（0）
        JsonNode r = call(admin, "PUT", "/api/" + type + "/orders/" + id, body(0, partnerField, partnerId));
        assertThat(fired).as(type + " 钩子触发").isEqualTo(1);
        assertThat(rowsAfterHook).as(type + " 编辑的定向更新影响 0 行").isZero();
        assertThat(r.path("code").asInt()).as(type + " 编辑输给提交 → 40903，不能是 40900：" + r).isEqualTo(40903);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, version, remark FROM " + table + " WHERE id=?", id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
        assertThat(row.get("remark")).as("编辑没有落库").isNull();
        // 收尾：不留审批中实例
        jdbc.update("UPDATE wf_approval_instance SET status='CANCELLED' WHERE status='PENDING' AND biz_id=?", id);
    }

    @Test void purchaseEditLosingToSubmitReturns40903() throws Exception {
        race("purchase", "pur_order", "supplierId", sup, () -> purchaseOrders.submit(armedIdSnapshot()));
    }

    @Test void salesEditLosingToSubmitReturns40903() throws Exception {
        race("sales", "sal_order", "customerId", cust, () -> salesOrders.submit(armedIdSnapshot()));
    }

    private static long lastArmed;
    private static long armedIdSnapshot() { return lastArmed; }
}
