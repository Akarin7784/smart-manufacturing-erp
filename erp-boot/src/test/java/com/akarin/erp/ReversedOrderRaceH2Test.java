package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;


/** #18(c) 正反序并发收货/发货（H2） */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReversedOrderRaceSupport.Hook.class)
class ReversedOrderRaceH2Test extends ReversedOrderRaceSupport {
    @Test void reversedOrderReceiveAndShipNoDeadlock() throws Exception {
        reversedOrderRace(loginAdmin());
    }
}
