package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** #22：取号繁忙 503 与 Idempotency-Key 并发（MySQL，-Pmysql-it） */
@org.junit.jupiter.api.Tag("mysql")
@org.springframework.test.context.ActiveProfiles("mysql")
@SpringBootTest
@AutoConfigureMockMvc
class DocNoBusyMySqlTest extends DocNoBusySupport {
    @Test void lockedSequenceRow503() throws Exception { lockedSequenceRowGives503AndMainPoolStaysFree(); }
    @Test void eightConcurrentSameKey() throws Exception { eightConcurrentSameKeyOneDocument(); }
}
