package com.akarin.erp.common.seq;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDocNoSequenceTest {
    @Test void lockWaitSqlPerDatabase() {
        assertThat(JdbcDocNoSequence.lockWaitSql("jdbc:mysql://h/erp", 2)).isEqualTo("SET SESSION innodb_lock_wait_timeout = 2");
        assertThat(JdbcDocNoSequence.lockWaitSql("jdbc:h2:mem:x", 2)).isEqualTo("SET LOCK_TIMEOUT 2000");
        assertThat(JdbcDocNoSequence.lockWaitSql("jdbc:postgresql://h/x", 2)).isNull();
        assertThat(JdbcDocNoSequence.lockWaitSql(null, 2)).isNull();
    }

    @Test void busyClassification() {
        assertThat(JdbcDocNoSequence.busy(new SQLTransientConnectionException("pool timeout"))).isTrue();
        assertThat(JdbcDocNoSequence.busy(new SQLException("lock wait", "HY000", 1205))).isTrue();
        assertThat(JdbcDocNoSequence.busy(new SQLException("deadlock", "40001", 1213))).isTrue();
        assertThat(JdbcDocNoSequence.busy(new SQLException("h2 timeout", "HYT00", 50200))).isTrue();
        assertThat(JdbcDocNoSequence.busy(new SQLException("x", new SQLException("inner", "HY000", 1205)))).isTrue();
        assertThat(JdbcDocNoSequence.busy(new SQLException("syntax", "42000", 1064))).isFalse();
    }
}
