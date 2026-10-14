package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

/** #18(c) 正反序并发收货/发货（MySql） */
@Tag("mysql")
@ActiveProfiles("mysql")@SpringBootTest
@AutoConfigureMockMvc
@Import(ReversedOrderRaceSupport.Hook.class)
class ReversedOrderRaceMySqlTest extends ReversedOrderRaceSupport {
    @Test void reversedOrderReceiveAndShipNoDeadlock() throws Exception {
        reversedOrderRace(login("admin", System.getenv().getOrDefault("ERP_ADMIN_PASSWORD", "admin123")));
    }
}
