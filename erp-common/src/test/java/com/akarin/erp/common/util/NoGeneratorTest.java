package com.akarin.erp.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class NoGeneratorTest {
    @AfterEach void reset() { NoGenerator.install(null); }

    @Test void fallbackFormatWhenNoSequenceInstalled() {
        assertThat(NoGenerator.installed()).isFalse();
        assertThat(NoGenerator.next("PY")).matches("PY\\d{14}\\d{7}");
        Set<String> seen = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 5000).parallel().forEach(i -> seen.add(NoGenerator.next("PY")));
        assertThat(seen).hasSize(5000);
    }

    @Test void installedSequenceFormatIsPrefixDayAndEightDigits() {
        Map<String, AtomicLong> db = new ConcurrentHashMap<>();
        NoGenerator.Sequence s = k -> db.computeIfAbsent(k, x -> new AtomicLong()).incrementAndGet();
        NoGenerator.install(s);
        String day = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        assertThat(NoGenerator.next("PO")).isEqualTo("PO" + day + "00000001");
        assertThat(NoGenerator.next("PO")).isEqualTo("PO" + day + "00000002");
        assertThat(NoGenerator.next("SO")).isEqualTo("SO" + day + "00000001");
        NoGenerator.uninstall(k -> 0);        // 不是当前安装的，不卸载
        assertThat(NoGenerator.installed()).isTrue();
        NoGenerator.uninstall(s);
        assertThat(NoGenerator.installed()).isFalse();
    }

    /**
     * #18 a：模拟两个应用实例（两个独立 ClassLoader，各自一份 NoGenerator 静态状态），同时大量取号。
     * 实例有 install 方法时（新代码），两边都接到同一个共享计数器（相当于共用一个数据库的 sys_doc_seq）。
     * 旧代码没有共享序列，只靠进程内序号 + 3 位随机数，同一秒内两实例必然出现重号。
     */
    @Test void twoInstancesNeverProduceSameNumber() throws Exception {
        URL classes = NoGenerator.class.getProtectionDomain().getCodeSource().getLocation();
        Map<String, AtomicLong> sharedDb = new ConcurrentHashMap<>();
        int perInstance = 20000;
        List<Callable<List<String>>> instances = new ArrayList<>();
        List<URLClassLoader> loaders = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            URLClassLoader l = new URLClassLoader(new URL[]{classes}, ClassLoader.getPlatformClassLoader());
            loaders.add(l);
            Class<?> gen = l.loadClass(NoGenerator.class.getName());
            assertThat(gen).isNotSameAs(NoGenerator.class);
            Method install = Arrays.stream(gen.getMethods()).filter(m -> m.getName().equals("install")).findFirst().orElse(null);
            if (install != null) {
                Class<?> seqType = install.getParameterTypes()[0];
                Object seq = Proxy.newProxyInstance(l, new Class<?>[]{seqType},
                        (p, m, a) -> m.getName().equals("next") ? sharedDb.computeIfAbsent((String) a[0], k -> new AtomicLong()).incrementAndGet() : null);
                install.invoke(null, seq);
            }
            Method next = gen.getMethod("next", String.class);
            instances.add(() -> {
                List<String> out = new ArrayList<>(perInstance);
                for (int n = 0; n < perInstance; n++) out.add((String) next.invoke(null, "PY"));
                return out;
            });
        }
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try {
            List<String> all = new ArrayList<>();
            for (Future<List<String>> f : ex.invokeAll(instances)) all.addAll(f.get());
            assertThat(new HashSet<>(all)).as("两个实例取到的号不能重复").hasSize(2 * perInstance);
        } finally {
            ex.shutdownNow();
            for (URLClassLoader l : loaders) l.close();
        }
    }

    /** #22-1：单号日期按 Asia/Shanghai，与 JVM 时区无关 */
    @Test void dayUsesShanghaiZoneNotJvmZone() {
        java.util.TimeZone old = java.util.TimeZone.getDefault();
        java.util.concurrent.atomic.AtomicLong n = new java.util.concurrent.atomic.AtomicLong();
        NoGenerator.Sequence seq = k -> n.incrementAndGet();
        NoGenerator.install(seq);
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Kiritimati"));
            String day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            assertThat(NoGenerator.next("PY")).startsWith("PY" + day);
        } finally { java.util.TimeZone.setDefault(old); NoGenerator.uninstall(seq); }
    }

    /** #22-5：事务外预取；using 内 next() 先消费预取的号，每次 using 从头消费，不够时现取 */
    @Test void reservedNumbersConsumedFirstAndReusableAcrossAttempts() {
        java.util.concurrent.atomic.AtomicLong n = new java.util.concurrent.atomic.AtomicLong();
        NoGenerator.Sequence seq = k -> n.incrementAndGet();
        NoGenerator.install(seq);
        try {
            java.util.function.Supplier<java.util.List<String>> work = NoGenerator.reserved(java.util.Map.of("PO", 1),
                    () -> java.util.List.of(NoGenerator.next("PO"), NoGenerator.next("PO")));
            assertThat(n.get()).as("预取在 work 之前").isEqualTo(1);
            java.util.List<String> a = work.get(), b = work.get();
            assertThat(a.get(0)).endsWith("00000001");
            assertThat(b.get(0)).as("重试复用同一个预取号").isEqualTo(a.get(0));
            assertThat(a.get(1)).as("不够时现取").isNotEqualTo(a.get(0));
            assertThat(NoGenerator.next("PO")).as("using 外不再消费预取").doesNotEndWith("00000001");
            assertThat(NoGenerator.reserveThen(java.util.Map.of("SO", 2), () -> NoGenerator.next("SO") + NoGenerator.next("SO"))).hasSize(36);
        } finally { NoGenerator.uninstall(seq); }
    }

    @Test void overEightDigitsStillUnique() {
        NoGenerator.Sequence seq = k -> 100_000_000L;
        NoGenerator.install(seq);
        try { assertThat(NoGenerator.next("PO")).endsWith("100000000"); }
        finally { NoGenerator.uninstall(seq); }
    }
}
