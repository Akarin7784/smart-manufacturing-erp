package com.akarin.erp.common.seq;

import com.akarin.erp.common.exception.BizException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** #22-2：取号就地重试——死锁最多 3 次、锁超时最多 2 次、拿不到取号连接不重试 */
class JdbcDocNoSequenceRetryTest {
    final AtomicInteger connections = new AtomicInteger(), upserts = new AtomicInteger();

    /** 前 failures 次 UPSERT 抛 failure，之后成功、读回 42 */
    DataSource ds(int failures, SQLException failure) throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(inv -> {
            connections.incrementAndGet();
            Connection c = mock(Connection.class);
            when(c.getAutoCommit()).thenReturn(true);
            PreparedStatement up = mock(PreparedStatement.class), q = mock(PreparedStatement.class);
            when(up.executeUpdate()).thenAnswer(i -> { if (upserts.incrementAndGet() <= failures) throw failure; return 1; });
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getLong(1)).thenReturn(42L);
            when(q.executeQuery()).thenReturn(rs);
            when(c.prepareStatement(anyString())).thenAnswer(i -> i.getArgument(0, String.class).startsWith("INSERT") ? up : q);
            return c;
        });
        return ds;
    }

    @Test void mysqlDeadlockRetriedInPlaceThenSucceeds() throws Exception {
        assertThat(new JdbcDocNoSequence(ds(2, new SQLException("deadlock", "40001", 1213))).next("PO20261014")).isEqualTo(42);
        assertThat(connections).as("每次重试换一个连接").hasValue(3);
    }

    @Test void h2DeadlockRetried() throws Exception {
        assertThat(new JdbcDocNoSequence(ds(1, new SQLException("deadlock", "40001", 40001))).next("K")).isEqualTo(42);
    }

    @Test void lockTimeoutRetriedOnceThenSucceeds() throws Exception {
        assertThat(new JdbcDocNoSequence(ds(1, new SQLException("lock wait", "HY000", 1205))).next("K")).isEqualTo(42);
    }

    @Test void deadlockThreeTimesIs50301() throws Exception {
        JdbcDocNoSequence s = new JdbcDocNoSequence(ds(99, new SQLException("deadlock", "40001", 1213)));
        assertThatThrownBy(() -> s.next("K")).isInstanceOf(BizException.class).extracting("code").isEqualTo(50301);
        assertThat(upserts).hasValue(3);
    }

    @Test void lockTimeoutTwiceIs50301() throws Exception {
        JdbcDocNoSequence s = new JdbcDocNoSequence(ds(99, new SQLException("h2 timeout", "HYT00", 50200)));
        assertThatThrownBy(() -> s.next("K")).isInstanceOf(BizException.class).extracting("code").isEqualTo(50301);
        assertThat(upserts).as("锁超时只再试 1 次").hasValue(2);
    }

    @Test void poolTimeoutNotRetried() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenAnswer(i -> { connections.incrementAndGet(); throw new SQLTransientConnectionException("doc-no - Connection is not available"); });
        assertThatThrownBy(() -> new JdbcDocNoSequence(ds).next("K")).isInstanceOf(BizException.class).extracting("code").isEqualTo(50301);
        assertThat(connections).hasValue(1);
    }

    @Test void otherSqlErrorNotRetriedAndNot503() throws Exception {
        JdbcDocNoSequence s = new JdbcDocNoSequence(ds(99, new SQLException("syntax", "42000", 1064)));
        assertThatThrownBy(() -> s.next("K")).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(upserts).hasValue(1);
    }
}
