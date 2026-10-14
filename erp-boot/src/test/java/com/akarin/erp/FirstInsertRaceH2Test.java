package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** #13：H2（默认 profile）上的同一交错 */
@SpringBootTest
@org.springframework.context.annotation.Import(FirstInsertRaceSupport.Hook.class)
@AutoConfigureMockMvc
class FirstInsertRaceH2Test extends FirstInsertRaceSupport {
    /** H2 的行锁等待是自旋（线程一直 RUNNABLE、SESSIONS 里也看不到 blocker）：以 B 进入建行语句 300ms 仍未返回为准 */
    @Override boolean bIsBlocked() {
        return bThread != null && !bReturned && System.currentTimeMillis() - bEnteredAt >= 300;
    }
    @Override boolean requireBlocked() { return true; }

    @Test void concurrentFirstStockInBothSucceed() throws Exception {
        firstReceiveRace(login("admin", "admin123"));
    }
}
