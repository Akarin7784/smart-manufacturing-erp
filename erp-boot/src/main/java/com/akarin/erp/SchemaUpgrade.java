package com.akarin.erp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 已有 MySQL 库的补丁：{@code schema.sql} 只用 {@code CREATE TABLE IF NOT EXISTS}，不会给已有表加列。
 * H2 内存库每次重启都会重建，不需要这里。幂等：列已存在就跳过。
 * <p>在所有单例初始化完成后、Web 服务器启动（接流量）之前执行（{@link SmartInitializingSingleton}，早于 Tomcat 启动的 finishRefresh），
 * 此时 schema.sql 已执行。多实例滚动发布时应先手工执行 README 里的 ALTER DDL，避免新旧实例并发改表。
 */
@Component
class SchemaUpgrade implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrade.class);
    private final JdbcTemplate jdbc;

    SchemaUpgrade(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void afterSingletonsInstantiated() { upgrade(); }

    void upgrade() {
        String product;
        try (java.sql.Connection c = jdbc.getDataSource().getConnection()) { product = c.getMetaData().getDatabaseProductName(); }
        catch (Exception e) { return; }
        if (product == null || !product.toLowerCase().contains("mysql")) return;
        for (String[] t : new String[][]{
                {"pur_order", "uk_pur_idem"}, {"sal_order", "uk_sal_idem"},
                {"inv_stock_txn", "uk_txn_idem"}, {"fin_payment", "uk_pay_idem"}}) {
            addColumn(t[0], "idem_user", "VARCHAR(64)");
            addColumn(t[0], "idem_key", "VARCHAR(128)");
            addColumn(t[0], "idem_hash", "CHAR(64)");
            addIndex(t[0], t[1], "(idem_user, idem_key)");
        }
        addColumn("sys_idempotency", "owner_token", "VARCHAR(64)");
        addColumn("sys_idempotency", "lease_until", "DATETIME");
    }

    private void addColumn(String table, String col, String type) {
        if (hasColumn(table, col)) return;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
        log.info("schema upgrade: {}.{} added", table, col);
    }

    private void addIndex(String table, String name, String cols) {
        if (hasIndex(table, name)) return;
        jdbc.execute("ALTER TABLE " + table + " ADD UNIQUE KEY " + name + " " + cols);
        log.info("schema upgrade: {}.{} added", table, name);
    }

    private boolean hasColumn(String table, String col) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, col);
        return n != null && n > 0;
    }

    private boolean hasIndex(String table, String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class, table, name);
        return n != null && n > 0;
    }
}
