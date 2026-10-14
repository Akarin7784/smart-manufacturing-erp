package com.akarin.erp.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单据号生成（骨架实现；生产可换成 Redis INCR / 号段）。
 * 格式：前缀 + yyyyMMddHHmmss + 4 位进程内序号 + 3 位随机数。
 * 以前只有 4 位随机数：同一秒内生成几十个号时撞唯一键（payment_no / order_no / bill_no）的概率不可忽略，
 * 撞了就是 DuplicateKey → 40900（CI 上 POST /api/finance/payments 偶发 40900 即此）。
 * 进程内序号保证单实例同一秒内 10000 个号不重复；随机位用于降低多实例间的碰撞。
 */
public final class NoGenerator {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt(10000));
    private NoGenerator() {}
    public static String next(String prefix) {
        int seq = Math.floorMod(SEQ.getAndIncrement(), 10000);
        return prefix + LocalDateTime.now().format(F) + String.format("%04d%03d", seq, ThreadLocalRandom.current().nextInt(1000));
    }
}
