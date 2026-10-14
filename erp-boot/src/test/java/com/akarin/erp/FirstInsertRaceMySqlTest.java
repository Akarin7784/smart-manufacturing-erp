package com.akarin.erp;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** #13：只在 MySQL 上跑（-Pmysql-it，CI MySQL smoke job） */
@Tag("mysql")
@ActiveProfiles("mysql")
@SpringBootTest
@org.springframework.context.annotation.Import(FirstInsertRaceSupport.Hook.class)
@AutoConfigureMockMvc
class FirstInsertRaceMySqlTest extends FirstInsertRaceSupport {
    @Override boolean bIsBlocked() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'", Integer.class);
        return n != null && n > 0;
    }
    @Override boolean requireBlocked() { return true; }

    @Test void concurrentFirstStockInBothSucceed() throws Exception {
        firstReceiveRace(login("admin", System.getenv().getOrDefault("ERP_ADMIN_PASSWORD", "admin123")));
    }
}
