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
     * 拿连接最多等 connectionTimeout（默认 1 秒），序列行锁每次最多等 lockWaitSeconds（默认 1 秒；锁超时会就地再试 1 次，MySQL 会话级
     * innodb_lock_wait_timeout / H2 LOCK_TIMEOUT），超时即 503/50301，不会长时间挂住请求。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcDocNoSequence(DataSourceProperties props,
                             @org.springframework.beans.factory.annotation.Value("${erp.doc-no.connection-timeout-ms:1000}") long connectionTimeoutMs,
                             @org.springframework.beans.factory.annotation.Value("${erp.doc-no.lock-wait-seconds:1}") int lockWaitSeconds) {
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

    /**
     * 序号行死锁 / 锁等待超时时就地重试（#22-2），不回滚整个业务事务（取号本来就在业务事务之前）。
     * <ul>
     * <li>死锁（MySQL 1213、H2 40001）：数据库立即判负，重试代价小，最多再试 2 次（共 3 次）。</li>
     * <li>锁等待超时（MySQL 1205、H2 50200 / HYT00）：每次已等满一个锁等待时长，只再试 1 次（共 2 次）。</li>
     * <li>拿不到取号连接（池满，1 秒超时）：说明取号已经拥堵，不重试，直接 503。</li>
     * </ul>
     * 每次重试换一个连接（H2 锁超时后 Hikari 会丢弃该连接），重试前随机退避 10~50ms。
     * 最坏延迟（默认 connectionTimeout 1s、lockWait 1s）：锁超时路径 2 ×（拿连接 ≤1s + 锁等待 1s）+ 退避 ≤50ms ≈ 4.05s；
     * 常见情形（连接立即拿到、行被外部长时间锁住）≈ 2 × 1s + 退避 ≈ 2.05s，与改动前单次 2s 的预算相当。
     */
    static final int MAX_DEADLOCK_ATTEMPTS = 3, MAX_TIMEOUT_ATTEMPTS = 2;

    @Override
    public long next(String key) {
        for (int attempt = 1; ; attempt++) {
            try {
                return once(key);
            } catch (SQLException e) {
                if (!busy(e)) throw new DataAccessResourceFailureException("取单据号失败: " + key, e);
                Kind k = kind(e);
                int max = k == Kind.DEADLOCK ? MAX_DEADLOCK_ATTEMPTS : k == Kind.LOCK_TIMEOUT ? MAX_TIMEOUT_ATTEMPTS : 1;
                if (attempt < max) {
                    log.warn("doc-no retry: key={} attempt={}/{} kind={} sqlState={} errorCode={}", key, attempt, max, k, e.getSQLState(), e.getErrorCode());
                    try { Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(10, 51)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw busyException(key, attempt, k, e); }
                    continue;
                }
                throw busyException(key, attempt, k, e);
            }
        }
    }

    enum Kind { DEADLOCK, LOCK_TIMEOUT, POOL_TIMEOUT }

    static Kind kind(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.sql.SQLTransientConnectionException) return Kind.POOL_TIMEOUT;
            if (t instanceof SQLException s) {
                if (s.getErrorCode() == 1213 || s.getErrorCode() == 40001) return Kind.DEADLOCK;
                if (s.getErrorCode() == 1205 || s.getErrorCode() == 50200 || "HYT00".equals(s.getSQLState())) return Kind.LOCK_TIMEOUT;
                if ("40001".equals(s.getSQLState())) return Kind.DEADLOCK;
            }
        }
        return Kind.LOCK_TIMEOUT;
    }

    private RuntimeException busyException(String key, int attempts, Kind k, SQLException e) {
        log.warn("doc-no busy: key={} attempts={} kind={} exception={} sqlState={} errorCode={}", key, attempts, k, e.getClass().getName(), e.getSQLState(), e.getErrorCode());
        return new com.akarin.erp.common.exception.BizException(com.akarin.erp.common.exception.ErrorCode.DOC_NO_BUSY);
    }

    private long once(String key) throws SQLException {
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
        }
    }

    @Override public void afterPropertiesSet() { NoGenerator.install(this); }

    @Override public void destroy() {
        NoGenerator.uninstall(this);
        if (owned != null) owned.close();
    }
}
