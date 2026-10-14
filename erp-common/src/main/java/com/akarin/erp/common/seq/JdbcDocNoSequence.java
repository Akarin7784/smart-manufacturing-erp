package com.akarin.erp.common.seq;

import com.akarin.erp.common.util.NoGenerator;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 数据库单据号序列（#18 a）：表 sys_doc_seq(seq_key, val)，
 * {@code INSERT ... ON DUPLICATE KEY UPDATE val = val + 1} 原子递增后在同一事务里读回，多实例共用同一个库时不会重号。
 * <p>用独立的小连接池、独立短事务（相当于 REQUIRES_NEW）：
 * 序列行锁只持有几毫秒，不跟业务事务一起持有到提交（否则所有收货/发货会在序列行上串行，并可能与库存行锁形成死锁）；
 * 也不占用业务连接池，避免“业务事务占着连接再等第二个连接”导致连接池耗尽。取号后业务回滚会留下空号，这是允许的。
 */
@Component
public class JdbcDocNoSequence implements NoGenerator.Sequence, InitializingBean, DisposableBean {
    static final String UPSERT = "INSERT INTO sys_doc_seq (seq_key, val) VALUES (?, 1) ON DUPLICATE KEY UPDATE val = val + 1";
    static final String SELECT = "SELECT val FROM sys_doc_seq WHERE seq_key = ?";

    private final DataSource dataSource;
    private final HikariDataSource owned;

    /** Spring 使用：按应用的数据源配置建一个独立小连接池 */
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcDocNoSequence(DataSourceProperties props) {
        HikariDataSource ds = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("doc-no");
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(0);
        this.owned = ds;
        this.dataSource = ds;
    }

    /** 测试使用：指定数据源（模拟另一个实例） */
    public JdbcDocNoSequence(DataSource dataSource) {
        this.dataSource = dataSource;
        this.owned = null;
    }

    @Override
    public long next(String key) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement up = c.prepareStatement(UPSERT)) { up.setString(1, key); up.executeUpdate(); }
                long v;
                try (PreparedStatement q = c.prepareStatement(SELECT)) {
                    q.setString(1, key);
                    try (ResultSet rs = q.executeQuery()) {
                        if (!rs.next()) throw new SQLException("sys_doc_seq row missing for key " + key);
                        v = rs.getLong(1);
                    }
                }
                c.commit();
                return v;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("取单据号失败: " + key, e);
        }
    }

    @Override public void afterPropertiesSet() { NoGenerator.install(this); }

    @Override public void destroy() {
        NoGenerator.uninstall(this);
        if (owned != null) owned.close();
    }
}
