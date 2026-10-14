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
    /**
     * 优先看 information_schema.innodb_trx 的 LOCK WAIT（需要 PROCESS 权限；CI 的 erp 账号没有）。
     * 没有权限时退回：B 进入建行语句 300ms 仍未返回（与 H2 用例同一判据）。
     */
    @Override boolean bIsBlocked() {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'", Integer.class);
            if (n != null && n > 0) return true;
        } catch (org.springframework.dao.DataAccessException noPrivilege) {
            // fall through
        }
        return bThread != null && !bReturned && System.currentTimeMillis() - bEnteredAt >= 300;
    }
    @Override boolean requireBlocked() { return true; }

    @Test void concurrentFirstStockInBothSucceed() throws Exception {
        firstReceiveRace(login("admin", System.getenv().getOrDefault("ERP_ADMIN_PASSWORD", "admin123")));
    }
}
