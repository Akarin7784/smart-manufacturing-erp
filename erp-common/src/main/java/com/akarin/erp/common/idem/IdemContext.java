package com.akarin.erp.common.idem;

import java.util.function.Supplier;

/**
 * 当前请求的幂等上下文：业务事务里写单据时从这里取 (用户, key)，与单据同事务落库（#22-9）。
 * 没有带 Idempotency-Key 的请求不写这两列。
 */
public final class IdemContext {
    private IdemContext() {}
    private static final ThreadLocal<String[]> CUR = new ThreadLocal<>();

    public static <T> T with(String user, String key, Supplier<T> work) {
        String[] prev = CUR.get();
        CUR.set(new String[]{user, key});
        try { return work.get(); }
        finally { if (prev == null) CUR.remove(); else CUR.set(prev); }
    }

    public static String user() { String[] c = CUR.get(); return c == null ? null : c[0]; }
    public static String key() { String[] c = CUR.get(); return c == null ? null : c[1]; }

    /** 把当前上下文写到单据上；无上下文时什么都不做（未带 Idempotency-Key） */
    public static void apply(java.util.function.BiConsumer<String, String> setter) {
        String[] c = CUR.get();
        if (c != null) setter.accept(c[0], c[1]);
    }
}
