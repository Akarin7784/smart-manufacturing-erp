package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** #13：H2（默认 profile）上的同一交错 */
@SpringBootTest
@org.springframework.context.annotation.Import(FirstInsertRaceSupport.Hook.class)
@AutoConfigureMockMvc
class FirstInsertRaceH2Test extends FirstInsertRaceSupport {
    @Test void concurrentFirstStockInBothSucceed() throws Exception {
        firstReceiveRace(loginAdmin());
    }
}
