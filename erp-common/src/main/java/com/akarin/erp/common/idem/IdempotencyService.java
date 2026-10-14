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
    /** PROCESSING 记录的租期（#22-9）：租期内同 key 返回 40904；过期后先按 (用户, key) 查已提交的单据，查到补成 DONE 并重放，查不到才接管 */
    private final Duration lease;

    public IdempotencyService(JdbcTemplate jdbc) { this(jdbc, 60); }

    @org.springframework.beans.factory.annotation.Autowired
    public IdempotencyService(JdbcTemplate jdbc,
                              @org.springframework.beans.factory.annotation.Value("${erp.idempotency.processing-lease-seconds:60}") long leaseSeconds) {
        this.jdbc = jdbc;
        this.lease = Duration.ofSeconds(leaseSeconds);
    }

    /** leaseValid：按数据库时钟 lease_until > LOCALTIMESTAMP */
    record Row(long id, String hash, String status, Long resultId, LocalDateTime expiresAt, LocalDateTime leaseUntil, boolean leaseValid) {
        Row(long id, String hash, String status, Long resultId, LocalDateTime expiresAt) { this(id, hash, status, resultId, expiresAt, null, false); }
    }

    /** 已认领的记录：行 id + 本次请求的持有者令牌（完成 / 释放都带令牌，被接管后原请求改不动记录） */
    record Claim(long rowId, String token) {}

    /**
     * @param key    请求头的值，null 表示没带
     * @param op     操作名（如 purchase.order.create），参与摘要：同一 key 用在不同接口视为参数不同
     * @param req    已校验的请求 DTO
     * @param work   真正的创建（自己负责事务）
     * @param idOf   从结果取单据 id
     * @param load   按 id 重新读取原单据
     * @param docTable 单据表（带 idem_user / idem_key 列及其唯一索引）：租期过期后按 (用户, key) 查是否已有提交的单据
     */
    public <T> T execute(String key, String op, Object req, String docTable, Supplier<T> work, Function<T, Long> idOf, Function<Long, T> load) {
        if (key == null) return work.get();
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH)
            throw new BizException(ErrorCode.BAD_REQUEST, HEADER + " 不能为空且最多 " + MAX_KEY_LENGTH + " 个字符");
        String user = currentUser();
        String hash = hash(op, req);
        for (int round = 0; ; round++) {
            Claim claimed = tryClaim(user, key, hash);
            if (claimed != null) return runClaimed(claimed, user, key, hash, docTable, work, idOf, load);
            Row r = find(user, key);
            if (r == null) { if (round < 3) continue; throw new BizException(ErrorCode.IDEMPOTENCY_IN_PROGRESS); }
            if (r.expiresAt().isBefore(now())) {
                // 过期：删掉旧记录（条件删除，并发时只有一个能删到），按新 key 再占位。
                // #22-11：删不掉（出错或反复争抢）时不拿过期的旧记录比对，直接 40904 让调用方稍后重试
                try {
                    jdbc.update("DELETE FROM sys_idempotency WHERE id = ? AND expires_at < ?", r.id(), Timestamp.valueOf(now()));
                } catch (RuntimeException e) {
                    log.warn("idempotency expired-record cleanup failed: rowId={} exception={}", r.id(), e.getClass().getName());
                    throw new BizException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
                }
                if (round < 3) continue;
                throw new BizException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
            }
            if (!r.hash().equals(hash)) throw new BizException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key 已被使用且参数不一致");
            if (DONE.equals(r.status()) && r.resultId() != null) return load.apply(r.resultId());
            // PROCESSING：租期按数据库时钟判断（多实例 JVM 时钟可能不一致）
            if (r.leaseValid()) throw new BizException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
            // 租期已过：原请求可能崩溃，也可能只是慢。先查单据是否已提交（单据与 (用户, key, 参数摘要) 同事务写入）
            Long docId;
            try {
                docId = replayableDocument(docTable, user, key, hash);
            } catch (RuntimeException ex) {
                // 不一致（或查单出错）：清掉这条租期已过的 PROCESSING 记录（条件删除，不会删到别人刚接管的），再抛出
                try {
                    jdbc.update("DELETE FROM sys_idempotency WHERE id = ? AND status = ? AND lease_until < LOCALTIMESTAMP", r.id(), PROCESSING);
                } catch (RuntimeException de) {
                    log.warn("idempotency stale-record cleanup failed: rowId={} exception={}", r.id(), de.getClass().getName());
                }
                throw ex;
            }
            if (docId != null) {
                jdbc.update("UPDATE sys_idempotency SET status = ?, result_id = ? WHERE id = ? AND status = ?", DONE, docId, r.id(), PROCESSING);
                log.info("idempotency lease expired, document already committed: rowId={} docId={}", r.id(), docId);
                return load.apply(docId);
            }
            // 没有单据：条件更新接管（只有一个请求能把过期租期改成新租期）
            String token = java.util.UUID.randomUUID().toString();
            // lease_until 用数据库时钟；旧记录（lease_until 为空）按 created_at（应用写入）+ 租期，在 find 里已算好
            int taken = jdbc.update("UPDATE sys_idempotency SET owner_token = ?, lease_until = TIMESTAMPADD(SECOND, ? + 1, LOCALTIMESTAMP) "
                            + "WHERE id = ? AND status = ? AND (lease_until < LOCALTIMESTAMP OR (lease_until IS NULL AND created_at < ?))",
                    token, lease.getSeconds(), r.id(), PROCESSING, Timestamp.valueOf(now().minus(lease)));
            if (taken == 1) {
                log.warn("idempotency lease expired, taking over: rowId={}", r.id());
                return runClaimed(new Claim(r.id(), token), user, key, hash, docTable, work, idOf, load);
            }
            if (round >= 3) throw new BizException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
        }
    }

    /**
     * 单据表里 (idem_user, idem_key) 对应、可以重放的单据 id；没有返回 null。
     * 单据上的参数摘要（idem_hash）与本次不同 → 409/40902：幂等记录被清理（24h）后同 key 换参数复用，不能重放旧单据。
     * 表名只来自代码里的常量。
     */
    Long replayableDocument(String docTable, String user, String key, String hash) {
        if (!DOC_UNIQUE.containsKey(docTable)) throw new IllegalArgumentException("unknown doc table " + docTable);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, idem_hash FROM " + docTable + " WHERE idem_user = ? AND idem_key = ?", user, key);
        if (rows.isEmpty()) return null;
        Object h = null, id = null;
        // 不能用 stream().map(getValue)：idem_hash 为 NULL（旧单据）时 findFirst 会 NPE
        for (Map.Entry<String, Object> e : rows.get(0).entrySet()) {
            if (e.getKey().equalsIgnoreCase("idem_hash")) h = e.getValue();
            else if (e.getKey().equalsIgnoreCase("id")) id = e.getValue();
        }
        // 旧单据没有摘要（NULL）按不一致处理
        if (h == null || !hash.equals(h.toString().trim()))
            throw new BizException(ErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key 已被使用且参数不一致");
        return ((Number) id).longValue();
    }

    /** 单据表 → 其 (idem_user, idem_key) 唯一约束名 */
    public static final Map<String, String> DOC_UNIQUE = Map.of(
            "pur_order", "uk_pur_idem", "sal_order", "uk_sal_idem", "inv_stock_txn", "uk_txn_idem", "fin_payment", "uk_pay_idem");

    /** 是否撞的是该单据表的幂等键唯一约束（只有这种才按“对方已提交”重放；其他唯一冲突照常抛出） */
    static boolean isIdemUniqueViolation(Throwable e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.dao.DuplicateKeyException || t instanceof java.sql.SQLIntegrityConstraintViolationException
                    || (t instanceof java.sql.SQLException s && "23505".equals(s.getSQLState()))) {
                for (Throwable u = t; u != null; u = u.getCause() == u ? null : u.getCause())
                    if (u.getMessage() != null && u.getMessage().toLowerCase(java.util.Locale.ROOT).contains(constraint)) return true;
            }
        }
        return false;
    }

    private <T> T runClaimed(Claim c, String user, String key, String hash, String docTable, Supplier<T> work, Function<T, Long> idOf, Function<Long, T> load) {
        boolean done = false;   // 只有标成 DONE 后才保留记录；其余任何异常路径都在 finally 里释放占位
        try {
            T result;
            try {
                result = IdemContext.with(user, key, hash, work);
            } catch (RuntimeException | Error e) {
                // 单据上 (idem_user, idem_key) 唯一：慢的原请求与接管者并发时，后提交的一方在这里撞唯一键回滚。
                // 只有撞的是本单据表的幂等键约束，才去查对方已提交的单据并（参数一致时）重放；其他错误照常抛出
                String constraint = DOC_UNIQUE.get(docTable);
                if (isIdemUniqueViolation(e, constraint)) {
                    log.warn("idempotency unique violation on {}: rowId={}", constraint, c.rowId());
                    Long docId = replayableDocument(docTable, user, key, hash);   // 不一致 → 40902
                    if (docId != null) {
                        markDone(c, docId, true);
                        done = true;
                        return load.apply(docId);
                    }
                }
                throw e;
            }
            markDone(c, idOf.apply(result), false);
            done = true;
            return result;
        } finally {
            if (!done) release(c);
        }
    }

    private void markDone(Claim c, long docId, boolean byOther) {
        try {
            // 不带令牌条件：单据已提交且 (用户, key) 唯一，记成 DONE 总是对的；令牌只用于判断是否被接管（记日志）
            int n = jdbc.update("UPDATE sys_idempotency SET status = ?, result_id = ? WHERE id = ? AND owner_token = ?", DONE, docId, c.rowId(), c.token());
            if (n == 0) {
                jdbc.update("UPDATE sys_idempotency SET status = ?, result_id = ? WHERE id = ?", DONE, docId, c.rowId());
                log.warn("idempotency record was taken over before mark-done: rowId={} docId={}", c.rowId(), docId);
            } else if (byOther) {
                log.warn("idempotency key committed by the other request, replaying: rowId={} docId={}", c.rowId(), docId);
            }
        } catch (RuntimeException e) {
            // 单据已提交；记录停在 PROCESSING，租期过后按 (用户, key) 查到单据补成 DONE，不会重复建单
            log.warn("idempotency mark-done failed: rowId={} exception={}", c.rowId(), e.getClass().getName());
        }
    }

    /** 失败释放：只删自己持有的记录（被接管后原请求失败不会删掉接管者的占位） */
    private void release(Claim c) {
        try { jdbc.update("DELETE FROM sys_idempotency WHERE id = ? AND status = ? AND owner_token = ?", c.rowId(), PROCESSING, c.token()); }
        catch (RuntimeException e) { log.warn("idempotency release failed: rowId={} exception={}", c.rowId(), e.getClass().getName()); }
    }

    private Claim tryClaim(String user, String key, String hash) {
        LocalDateTime now = now();
        String token = java.util.UUID.randomUUID().toString();
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        try {
            jdbc.update(c -> {
                PreparedStatement ps = c.prepareStatement("INSERT INTO sys_idempotency (user_name, idem_key, req_hash, status, created_at, expires_at, owner_token, lease_until) VALUES (?, ?, ?, ?, ?, ?, ?, TIMESTAMPADD(SECOND, ? + 1, LOCALTIMESTAMP))",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, user); ps.setString(2, key); ps.setString(3, hash); ps.setString(4, PROCESSING);
                ps.setTimestamp(5, Timestamp.valueOf(now)); ps.setTimestamp(6, Timestamp.valueOf(now.plus(TTL)));
                ps.setString(7, token); ps.setLong(8, lease.getSeconds());
                return ps;
            }, kh);
        } catch (DuplicateKeyException e) {
            return null;
        }
        List<Map<String, Object>> keys = kh.getKeyList();
        Object id = keys.isEmpty() ? null : keys.get(0).values().iterator().next();
        return new Claim(((Number) id).longValue(), token);
    }

    private Row find(String user, String key) {
        List<Row> l = jdbc.query("SELECT id, req_hash, status, result_id, expires_at, lease_until, CASE WHEN lease_until > LOCALTIMESTAMP THEN 1 ELSE 0 END FROM sys_idempotency WHERE user_name = ? AND idem_key = ?",
                (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4), rs.getTimestamp(5).toLocalDateTime(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toLocalDateTime(), rs.getInt(7) == 1), user, key);
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
