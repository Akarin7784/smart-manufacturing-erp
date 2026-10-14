package com.akarin.erp.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单据号生成。
 * <p>运行时（Spring 容器内）由 {@code JdbcDocNoSequence} 安装数据库序列：号 = 前缀 + yyyyMMdd + 8 位序号，
 * 序号来自表 {@code sys_doc_seq} 按 (前缀 + 日期) 原子递增，多实例共用同一个库时全局唯一（#18 a）。
 * <p>未安装序列时（纯单元测试、没有数据库）退回进程内实现：前缀 + yyyyMMddHHmmss + 4 位进程内序号 + 3 位随机数，
 * 只保证单实例内不重复，生产环境不会走到这里。
 */
public final class NoGenerator {
    /** 按 key 原子递增并返回新值（从 1 开始） */
    public interface Sequence {
        long next(String key);
    }

    /** 单号日期按业务时区（#22-1）：不跟随 JVM/容器时区，避免 UTC 容器里北京时间 8 点才换日 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 序号 8 位；超过后仍唯一（变成 9 位），只记 WARN（#22-7） */
    static final long SEQ_MAX = 99_999_999L;
    /** 当前线程在事务外预取的号（{@link #using}）；next() 优先消费 */
    private static final ThreadLocal<Map<String, Deque<String>>> RESERVED = new ThreadLocal<>();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt(10000));
    private static volatile Sequence sequence;

    private NoGenerator() {}

    public static void install(Sequence s) { sequence = s; }

    public static void uninstall(Sequence s) { if (sequence == s) sequence = null; }

    public static boolean installed() { return sequence != null; }

    public static String next(String prefix) {
        Map<String, Deque<String>> r = RESERVED.get();
        if (r != null) {
            Deque<String> q = r.get(prefix);
            if (q != null && !q.isEmpty()) return q.poll();
        }
        return fetch(prefix);
    }

    /** 直接取号（不看预取）。调用方应在拿业务连接/开启事务之前调用（#22-5） */
    public static String fetch(String prefix) {
        Sequence s = sequence;
        if (s != null) {
            String key = prefix + LocalDate.now(ZONE).format(DAY);
            long v = s.next(key);
            if (v > SEQ_MAX) org.slf4j.LoggerFactory.getLogger(NoGenerator.class).warn("doc-no sequence exceeded 8 digits: key={} value={}", key, v);
            return key + String.format("%08d", v);
        }
        int seq = Math.floorMod(SEQ.getAndIncrement(), 10000);
        return prefix + LocalDateTime.now(ZONE).format(F) + String.format("%04d%03d", seq, ThreadLocalRandom.current().nextInt(1000));
    }

    /** 事务外按前缀预取若干个号 */
    public static Map<String, java.util.List<String>> reserve(Map<String, Integer> counts) {
        Map<String, java.util.List<String>> out = new HashMap<>();
        counts.forEach((p, n) -> {
            java.util.List<String> l = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) l.add(fetch(p));
            out.put(p, l);
        });
        return out;
    }

    /** 先在事务外取好号，再执行 work（work 内开业务事务）：等号时不占业务连接（#22-5） */
    public static <T> T reserveThen(Map<String, Integer> counts, Supplier<T> work) {
        return using(reserve(counts), work);
    }

    /**
     * 事务外先预取号，返回给 TxRetry 的工作单元：每次（重试）尝试都从头消费这批号——回滚的尝试没有落库，号可复用。
     */
    public static <T> Supplier<T> reserved(Map<String, Integer> counts, Supplier<T> work) {
        Map<String, java.util.List<String>> r = reserve(counts);
        return () -> using(r, work);
    }

    /**
     * 在 work 内让 {@link #next} 先消费预取的号；每次调用都从头消费（整事务重试时，上一次回滚的号可以复用），
     * 不够时才现取。
     */
    public static <T> T using(Map<String, java.util.List<String>> reserved, Supplier<T> work) {
        Map<String, Deque<String>> prev = RESERVED.get();
        Map<String, Deque<String>> cur = new HashMap<>();
        reserved.forEach((p, l) -> cur.put(p, new ArrayDeque<>(l)));
        RESERVED.set(cur);
        try { return work.get(); }
        finally { if (prev == null) RESERVED.remove(); else RESERVED.set(prev); }
    }
}
