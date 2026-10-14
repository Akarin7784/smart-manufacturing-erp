package com.akarin.erp.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class NoGeneratorTest {
    @Test void formatPrefixTimestampSevenDigits() {
        assertThat(NoGenerator.next("PY")).matches("PY\\d{14}\\d{7}");
    }

    /** 旧实现（同一秒 4 位随机）在 5000 个号里几乎必然重复；新实现进程内同一秒 10000 个号内不重复 */
    @Test void noDuplicatesUnderConcurrentBurst() {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 5000).parallel().forEach(i -> seen.add(NoGenerator.next("PY")));
        assertThat(seen).hasSize(5000);
        Set<String> full = new HashSet<>();
        for (int i = 0; i < 5000; i++) full.add(NoGenerator.next("PO"));
        assertThat(full).hasSize(5000);
    }
}
