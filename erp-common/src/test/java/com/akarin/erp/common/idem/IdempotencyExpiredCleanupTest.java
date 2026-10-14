package com.akarin.erp.common.idem;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.util.NoGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** #22-11：过期记录清理失败 / 反复删不掉时返回 40904，不拿过期的旧记录比对或重放 */
class IdempotencyExpiredCleanupTest {
    final AtomicInteger deletes = new AtomicInteger(), work = new AtomicInteger();

    /** 占位总是撞唯一键，查到的是一条过期记录；DELETE 的行为由 deleteFails 决定（true 抛异常，false 删到 0 行） */
    IdempotencyService svc(String hash, boolean deleteFails) {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public int update(PreparedStatementCreator psc, KeyHolder kh) { throw new DuplicateKeyException("dup"); }
            @SuppressWarnings("unchecked")
            @Override public <T> List<T> query(String sql, RowMapper<T> rm, Object... args) {
                return (List<T>) List.of(new IdempotencyService.Row(7L, hash, "DONE", 99L, LocalDateTime.now(NoGenerator.ZONE).minusHours(1)));
            }
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE")) { deletes.incrementAndGet(); if (deleteFails) throw new DataAccessResourceFailureException("db down"); return 0; }
                return 1;
            }
        };
        return new IdempotencyService(jdbc);
    }

    int run(IdempotencyService s, String req) {
        try {
            Long r = s.execute("k", "op", req, "pur_order", () -> { work.incrementAndGet(); return 1L; }, x -> x, id -> -id);
            return r == -99L ? -99 : 0;   // -99：重放了过期记录
        } catch (BizException e) { return e.getCode(); }
    }

    @Test void cleanupFailureIs40904NotStaleComparison() {
        assertThat(run(svc("stale-hash-differs", true), "new params")).as("旧代码：拿过期记录比对 → 40902").isEqualTo(40904);
        assertThat(deletes).hasValue(1);
        assertThat(work).hasValue(0);
    }

    @Test void cleanupFailureWithSameHashDoesNotReplayStaleRecord() {
        assertThat(run(svc(IdempotencyService.hash("op", "same"), true), "same")).as("旧代码：重放过期记录（-99）").isEqualTo(40904);
    }

    @Test void expiredRowThatNeverGoesAwayIs40904() {
        assertThat(run(svc(IdempotencyService.hash("op", "same"), false), "same")).as("旧代码：几轮后重放过期记录").isEqualTo(40904);
        assertThat(deletes.get()).isGreaterThanOrEqualTo(1);
        assertThat(work).hasValue(0);
    }
}
