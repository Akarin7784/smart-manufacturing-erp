package com.akarin.erp;

import com.akarin.erp.common.util.NoGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #22-2（H2）：序号行被外部锁住 1.4s 后释放，锁等待 1s。旧代码第一次锁超时就 503；
 * 新代码就地重试一次（换连接），第二次拿到锁 → 200，单号正常递增。
 */
@SpringBootTest(properties = "erp.doc-no.lock-wait-seconds=1")
@AutoConfigureMockMvc
class DocNoRetryH2Test extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSourceProperties props;

    @Test void lockTimeoutRetriedInPlaceThenSucceeds() throws Exception {
        String admin = loginAdmin();
        long wh = mdm(admin, "warehouses", "RW"), mat = mdm(admin, "materials", "RM");
        String key = "SMI" + LocalDate.now(NoGenerator.ZONE).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        jdbc.update("INSERT INTO sys_doc_seq (seq_key, val) VALUES (?, 0) ON DUPLICATE KEY UPDATE val = val", key);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try (Connection ext = DriverManager.getConnection(props.determineUrl(), props.determineUsername(), props.determinePassword())) {
            ext.setAutoCommit(false);
            try (PreparedStatement ps = ext.prepareStatement("SELECT val FROM sys_doc_seq WHERE seq_key = ? FOR UPDATE")) { ps.setString(1, key); ps.executeQuery().close(); }
            long t0 = System.nanoTime();
            Future<Integer> f = ex.submit(() -> code(admin, "POST", "/api/inventory/in",
                    "{\"materialId\":" + mat + ",\"warehouseId\":" + wh + ",\"qty\":\"1\"}"));
            Thread.sleep(1400);
            ext.rollback();
            assertThat(f.get(10, TimeUnit.SECONDS)).as("第一次锁超时后就地重试成功").isZero();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("重试在预算内：≈1s 超时 + 退避 + 第二次").isBetween(1000L, 3500L);
        } finally { ex.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_stock_txn WHERE material_id=?", Integer.class, mat)).isEqualTo(1);
    }
}
