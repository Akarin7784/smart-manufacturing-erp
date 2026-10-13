package com.akarin.erp.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/** 单据号生成（骨架实现；生产可换成 Redis INCR / 号段） */
public final class NoGenerator {
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private NoGenerator() {}
    public static String next(String prefix) {
        return prefix + LocalDateTime.now().format(F) + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
