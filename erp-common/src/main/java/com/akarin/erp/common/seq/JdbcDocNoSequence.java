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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JdbcDocNoSequence.class);
    private final DataSource dataSource;
    private final HikariDataSource owned;

    /**
     * Spring 使用：按应用的数据源配置建一个独立小连接池（#22-3/5）。
     * 拿连接最多等 connectionTimeout（默认 1 秒），序列行锁最多等 lockWaitSeconds（默认 2 秒，MySQL 会话级
     * innodb_lock_wait_timeout / H2 LOCK_TIMEOUT），超时即 503/50301，不会长时间挂住请求。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcDocNoSequence(DataSourceProperties props,
                             @org.springframework.beans.factory.annotation.Value("${erp.doc-no.connection-timeout-ms:1000}") long connectionTimeoutMs,
                             @org.springframework.beans.factory.annotation.Value("${erp.doc-no.lock-wait-seconds:2}") int lockWaitSeconds) {
        HikariDataSource ds = props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        ds.setPoolName("doc-no");
        ds.setMaximumPoolSize(4);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(connectionTimeoutMs);
        String init = lockWaitSql(props.determineUrl(), lockWaitSeconds);
        if (init != null) ds.setConnectionInitSql(init);
        this.owned = ds;
        this.dataSource = ds;
    }

    static String lockWaitSql(String url, int seconds) {
        if (url == null) return null;
        if (url.startsWith("jdbc:mysql:")) return "SET SESSION innodb_lock_wait_timeout = " + seconds;
        if (url.startsWith("jdbc:h2:")) return "SET LOCK_TIMEOUT " + (seconds * 1000L);
        return null;
    }

    /** 拿不到取号连接（池满超时）或序列行锁等待超时/死锁 → 取号繁忙；其他错误照旧（500） */
    static boolean busy(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException) return true;
            if (t instanceof SQLException s && (java.util.Set.of(1205, 1213, 50200, 40001).contains(s.getErrorCode())
                    || "HYT00".equals(s.getSQLState()) || "40001".equals(s.getSQLState()))) return true;
        }
        return false;
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
                // 锁超时后 Hikari 会把连接标记为损坏并关闭，rollback/恢复 autoCommit 再失败时保留原始异常（用于判断 503）
                try { c.rollback(); } catch (SQLException r) { e.addSuppressed(r); }
                throw e;
            } finally {
                try { c.setAutoCommit(auto); } catch (SQLException ignore) { /* 连接已损坏，归还时会被丢弃 */ }
            }
        } catch (SQLException e) {
            if (busy(e)) {
                log.warn("doc-no busy: key={} exception={} sqlState={} errorCode={}", key, e.getClass().getName(), e.getSQLState(), e.getErrorCode());
                throw new com.akarin.erp.common.exception.BizException(com.akarin.erp.common.exception.ErrorCode.DOC_NO_BUSY);
            }
            throw new DataAccessResourceFailureException("取单据号失败: " + key, e);
        }
    }

    @Override public void afterPropertiesSet() { NoGenerator.install(this); }

    @Override public void destroy() {
        NoGenerator.uninstall(this);
        if (owned != null) owned.close();
    }
}
