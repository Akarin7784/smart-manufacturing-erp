package com.akarin.erp.common.idem;

import java.util.function.Supplier;

/**
 * 当前请求的幂等上下文 (用户, key, 参数摘要)。只写到单据<b>头</b>表（采购单、销售单、收付款）上，与单据同事务落库（#22-9）。
 * 库存流水不读这里：只有手工出入库（流水本身就是单据）由控制器显式把 {@link #current()} 作为参数传给库存服务，
 * 采购收货 / 销售发货生成的多条流水不带幂等键。
 */
public final class IdemContext {
    private IdemContext() {}

    /** 写到单据上的幂等键 */
    public record Key(String user, String key, String hash) {}

    private static final ThreadLocal<Key> CUR = new ThreadLocal<>();

    public static <T> T with(String user, String key, String hash, Supplier<T> work) {
        Key prev = CUR.get();
        CUR.set(new Key(user, key, hash));
        try { return work.get(); }
        finally { if (prev == null) CUR.remove(); else CUR.set(prev); }
    }

    /** 当前请求的幂等键；没带 Idempotency-Key 时为 null */
    public static Key current() { return CUR.get(); }

    /** 单据头写入幂等键；无上下文时什么都不做 */
    public static void apply(Setter setter) {
        Key c = CUR.get();
        if (c != null) setter.set(c.user(), c.key(), c.hash());
    }

    @FunctionalInterface
    public interface Setter { void set(String user, String key, String hash); }
}
