package com.akarin.erp.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt(10000));
    private static volatile Sequence sequence;

    private NoGenerator() {}

    public static void install(Sequence s) { sequence = s; }

    public static void uninstall(Sequence s) { if (sequence == s) sequence = null; }

    public static boolean installed() { return sequence != null; }

    public static String next(String prefix) {
        Sequence s = sequence;
        if (s != null) {
            String key = prefix + LocalDate.now().format(DAY);
            return key + String.format("%08d", s.next(key));
        }
        int seq = Math.floorMod(SEQ.getAndIncrement(), 10000);
        return prefix + LocalDateTime.now().format(F) + String.format("%04d%03d", seq, ThreadLocalRandom.current().nextInt(1000));
    }
}
