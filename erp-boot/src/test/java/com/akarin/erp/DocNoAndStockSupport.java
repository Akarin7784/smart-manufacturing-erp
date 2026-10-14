package com.akarin.erp;

import com.akarin.erp.common.seq.JdbcDocNoSequence;
import com.akarin.erp.common.util.NoGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** #18 a / b / e 的集成用例，H2 与 MySQL 各一个子类 */
abstract class DocNoAndStockSupport extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSourceProperties props;

    static String today() { return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")); }

    /** (a) 两个“实例”（各自独立连接池的序列）并发取同一个 key：值不重复且连续 1..N；运行中的应用已安装数据库序列 */
    void docNoTwoInstancesNoDuplicates() throws Exception {
        JdbcDocNoSequence i1 = new JdbcDocNoSequence(props), i2 = new JdbcDocNoSequence(props);
        // 构造即建独立 Hikari 池；这里不调用 afterPropertiesSet，避免替换应用里已安装的序列
        String key = "TST" + UUID.randomUUID().toString().substring(0, 8);
        int threadsPerInstance = 4, perThread = 250;
        ExecutorService ex = Executors.newFixedThreadPool(2 * threadsPerInstance);
        List<Future<List<Long>>> fs = new ArrayList<>();
        try {
            for (JdbcDocNoSequence inst : List.of(i1, i2))
                for (int t = 0; t < threadsPerInstance; t++)
                    fs.add(ex.submit(() -> { List<Long> out = new ArrayList<>(); for (int n = 0; n < perThread; n++) out.add(inst.next(key)); return out; }));
            Set<Long> all = new HashSet<>();
            int total = 0;
            for (Future<List<Long>> f : fs) { List<Long> l = f.get(60, TimeUnit.SECONDS); total += l.size(); all.addAll(l); }
            assertThat(total).isEqualTo(2 * threadsPerInstance * perThread);
            assertThat(all).as("两实例并发取号不重复").hasSize(total);
            assertThat(Collections.min(all)).isEqualTo(1L);
            assertThat(Collections.max(all)).isEqualTo((long) total);
        } finally {
            ex.shutdownNow();
            i1.destroy(); i2.destroy();
        }
        assertThat(NoGenerator.installed()).as("应用启动后已安装数据库序列").isTrue();
        String admin = loginAdmin();
        long sup = mdm(admin, "suppliers", "NS"), wh = mdm(admin, "warehouses", "NW"), mat = mdm(admin, "materials", "NM");
        String no = ok(admin, "POST", "/api/purchase/orders", "{\"supplierId\":" + sup + ",\"warehouseId\":" + wh +
                ",\"items\":[{\"materialId\":" + mat + ",\"qty\":\"1\",\"price\":\"1\"}]}").path("order").path("orderNo").asText();
        assertThat(no).matches("PO" + today() + "\\d{8}");
        Long val = jdbc.queryForObject("SELECT val FROM sys_doc_seq WHERE seq_key=?", Long.class, "PO" + today());
        assertThat(Long.parseLong(no.substring(10))).isLessThanOrEqualTo(val);
    }

    /** (b) 库存流水号前缀 SMI / SMO，不再与销售单 SO 重合 */
    void movementPrefixDistinctFromSalesOrder() throws Exception {
        String admin = loginAdmin();
        long wh = mdm(admin, "warehouses", "PW"), mat = mdm(admin, "materials", "PM");
        String in = ok(admin, "POST", "/api/inventory/in", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"3\"}").path("txnNo").asText();
        String out = ok(admin, "POST", "/api/inventory/out", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"1\"}").path("txnNo").asText();
        assertThat(in).startsWith("SMI");
        assertThat(out).startsWith("SMO").doesNotStartWith("SO2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE material_id=? AND (txn_no LIKE 'SO%' OR txn_no LIKE 'SI%')", Integer.class, mat)).isZero();
    }

    /** (e) 逻辑删除的库存行：入库复活（deleted=0、从 0 起算、version+1），出库仍是库存不足且不改行 */
    void softDeletedStockRowIsRevivedOnStockIn() throws Exception {
        String admin = loginAdmin();
        long wh = mdm(admin, "warehouses", "DW"), mat = mdm(admin, "materials", "DM");
        jdbc.update("INSERT INTO inv_stock (material_id, warehouse_id, qty, version, deleted) VALUES (?, ?, 5, 3, 1)", mat, wh);

        JsonNode outR = call(admin, "POST", "/api/inventory/out", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"1\"}");
        assertThat(outR.path("code").asInt()).as("已删除行不能出库：" + outR).isEqualTo(400);
        assertThat(jdbc.queryForMap("SELECT qty, deleted FROM inv_stock WHERE material_id=? AND warehouse_id=?", mat, wh).get("deleted").toString()).isEqualTo("1");

        JsonNode inR = call(admin, "POST", "/api/inventory/in", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"2\"}");
        assertThat(inR.path("code").asInt()).as("入库应复活已删除的库存行：" + inR).isZero();
        assertThat(new BigDecimal(inR.path("data").path("balanceAfter").asText())).isEqualByComparingTo("2");
        Map<String, Object> row = jdbc.queryForMap("SELECT qty, version, deleted FROM inv_stock WHERE material_id=? AND warehouse_id=?", mat, wh);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock WHERE material_id=? AND warehouse_id=?", Integer.class, mat, wh)).isEqualTo(1);
        assertThat(((Number) row.get("deleted")).intValue()).isZero();
        assertThat(new BigDecimal(row.get("qty").toString())).as("旧数量 5 作废，从 0 起算").isEqualByComparingTo("2");
        assertThat(((Number) row.get("version")).intValue()).as("复活 +1、更新 +1").isEqualTo(5);
        // 复活后照常出入库
        assertThat(new BigDecimal(ok(admin, "POST", "/api/inventory/out", "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"0.5\"}").path("balanceAfter").asText())).isEqualByComparingTo("1.5");
    }
}
