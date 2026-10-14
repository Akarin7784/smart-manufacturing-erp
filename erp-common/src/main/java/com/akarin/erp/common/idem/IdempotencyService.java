package com.akarin.erp.common.idem;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.common.util.NoGenerator;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 创建接口的 {@code Idempotency-Key} 支持（#22）。
 * <ul>
 * <li>不带请求头：直接执行，行为不变。</li>
 * <li>表 sys_idempotency 上 UNIQUE(user_name, idem_key)：先以 PROCESSING 占位（独立短语句、自动提交），
 *     同一用户同一 key 并发只有一个请求能占到，只会生成一张单据。</li>
 * <li>占位已存在：参数摘要不同 → 409/40902；仍在处理 → 409/40904；已成功 → 返回原单据。</li>
 * <li>执行失败（含 503/50301 取号繁忙、校验失败）：删除占位，key 不被占用，用同一 key 重试会正常创建且只建一张。</li>
 * <li>记录保留 24 小时；过期的 key 视为新 key。</li>
 * </ul>
 * 参数摘要 = SHA-256(操作名 + 校验后的请求 DTO 的规范 JSON)：字段按名称排序，BigDecimal 先 stripTrailingZeros（"1.0" 与 "1" 相同）。
 */
@Service
public class IdempotencyService {
    public static final String HEADER = "Idempotency-Key";
    static final Duration TTL = Duration.ofHours(24);
    static final int MAX_KEY_LENGTH = 128;
    static final String PROCESSING = "PROCESSING", DONE = "DONE";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .addModule(new JavaTimeModule())
            .addModule(new SimpleModule().addSerializer(BigDecimal.class, new StdSerializer<>(BigDecimal.class) {
                @Override public void serialize(BigDecimal v, JsonGenerator g, SerializerProvider p) throws IOException {
                    g.writeString(v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString());
                }
            }))
            .build();

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Row(long id, String hash, String status, Long resultId, LocalDateTime expiresAt) {}

    /**
     * @param key    请求头的值，null 表示没带
     * @param op     操作名（如 purchase.order.create），参与摘要：同一 key 用在不同接口视为参数不同
     * @param req    已校验的请求 DTO
     * @param work   真正的创建（自己负责事务）
     * @param idOf   从结果取单据 id
     * @param load   按 id 重新读取原单据
     */
    public <T> T execute(String key, String op, Object req, Supplier<T> work, Function<T, Long> idOf, Function<Long, T> load) {
        if (key == null) return work.get();
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH)
            throw new BizException(ErrorCode.BAD_REQUEST, HEADER + " 不能为空且最多 " + MAX_KEY_LENGTH + " 个字符");
        String user = currentUser();
        String hash = hash(op, req);
        for (int round = 0; ; round++) {
            Long claimed = tryClaim(user, key, hash);
            if (claimed != null) return runClaimed(claimed, work, idOf);
            Row r = find(user, key);
            if (r == null) { if (round < 3) continue; throw new BizException(ErrorCode.IDEMPOTENCY_IN_PROGRESS); }
            if (r.expiresAt().isBefore(now())) {
                // 过期：删掉旧记录（条件删除，并发时只有一个能删到），按新 key 再占位
                jdbc.update("DELETE FROM sys_idempotency WHERE id = ? AND expires_at < ?", r.id(), Timestamp.valueOf(now()));
                if (round < 3) continue;
            }
            if (!r.hash().equals(hash)) throw new BizException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key 已被使用且参数不一致");
            if (!DONE.equals(r.status()) || r.resultId() == null) throw new BizException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
            return load.apply(r.resultId());
        }
    }

    private <T> T runClaimed(long rowId, Supplier<T> work, Function<T, Long> idOf) {
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error e) {
            release(rowId);
            throw e;
        }
        try {
            jdbc.update("UPDATE sys_idempotency SET status = ?, result_id = ? WHERE id = ?", DONE, idOf.apply(result), rowId);
        } catch (RuntimeException e) {
            // 单据已提交；标记失败时记录保持 PROCESSING，同 key 重试返回 40904 直到过期，不会重复建单
            log.warn("idempotency mark-done failed: rowId={} exception={}", rowId, e.getClass().getName());
        }
        return result;
    }

    private void release(long rowId) {
        try { jdbc.update("DELETE FROM sys_idempotency WHERE id = ? AND status = ?", rowId, PROCESSING); }
        catch (RuntimeException e) { log.warn("idempotency release failed: rowId={} exception={}", rowId, e.getClass().getName()); }
    }

    private Long tryClaim(String user, String key, String hash) {
        LocalDateTime now = now();
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        try {
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, user); ps.setString(2, key); ps.setString(3, hash); ps.setString(4, PROCESSING);
                ps.setTimestamp(5, Timestamp.valueOf(now)); ps.setTimestamp(6, Timestamp.valueOf(now.plus(TTL)));
                return ps;
            }, kh);
        } catch (DuplicateKeyException e) {
            return null;
        }
        List<Map<String, Object>> keys = kh.getKeyList();
        Object id = keys.isEmpty() ? null : keys.get(0).values().iterator().next();
        return ((Number) id).longValue();
    }

    private Row find(String user, String key) {
        List<Row> l = jdbc.query("SELECT id, req_hash, status, result_id, expires_at FROM sys_idempotency WHERE user_name = ? AND idem_key = ?",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getTimestamp(5).toLocalDateTime()), user, key);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 每小时清理过期记录（过期的 key 在 execute 里也会被当作新 key 处理，这里只是回收空间） */
    @org.springframework.scheduling.annotation.Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    public int purgeExpired() {
        return jdbc.update("DELETE FROM sys_idempotency WHERE expires_at < ?", Timestamp.valueOf(now()));
    }

    static LocalDateTime now() { return LocalDateTime.now(NoGenerator.ZONE); }

    static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a.getName() == null ? "anonymous" : a.getName();
    }

    public static String hash(String op, Object req) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((op + "\n" + CANONICAL.writeValueAsString(req)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException("idempotency hash failed", e);
        }
    }
}
