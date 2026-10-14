package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** #22-9 租期（MySQL，-Pmysql-it） */
@org.junit.jupiter.api.Tag("mysql")
@org.springframework.test.context.ActiveProfiles("mysql")
@SpringBootTest(properties = "erp.idempotency.processing-lease-seconds=2")
@AutoConfigureMockMvc
class IdemLeaseMySqlTest extends IdemLeaseSupport {
    @org.springframework.beans.factory.annotation.Autowired SchemaUpgrade upgrade;

    /** 已有库升级：去掉列与唯一键后，启动补丁（SchemaUpgrade）把它们加回来；再跑一次不报错（幂等） */
    @Test void schemaUpgradeAddsMissingColumnsIdempotently() {
        jdbc.execute("ALTER TABLE fin_payment DROP INDEX uk_pay_idem, DROP COLUMN idem_user, DROP COLUMN idem_key, DROP COLUMN idem_hash");
        jdbc.execute("ALTER TABLE sys_idempotency DROP COLUMN owner_token, DROP COLUMN lease_until");
        upgrade.upgrade();
        upgrade.upgrade();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='fin_payment' AND COLUMN_NAME IN ('idem_user','idem_key','idem_hash')", Integer.class)).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='fin_payment' AND INDEX_NAME='uk_pay_idem' AND NON_UNIQUE=0", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME='sys_idempotency' AND COLUMN_NAME IN ('owner_token','lease_until')", Integer.class)).isEqualTo(2);
    }

    @Test void committedButProcessingReplays() throws Exception { committedButProcessingReplaysAfterLease(); }
    @Test void retryWhileLeaseValid() throws Exception { retryWhileLeaseValidIs40904(); }
    @Test void twoReclaimersOneDoc() throws Exception { twoReclaimersExactlyOneDocument(); }
    @Test void documentUniqueConstraint() throws Exception { documentUniqueConstraintBlocksSecondInsert(); }
    @Test void crashThenLeaseCreatesOne() throws Exception { crashThenLeaseExpiredCreatesOne(); }
    @Test void slowOriginalAndTakeover() throws Exception { slowOriginalAndTakeoverOneDocument(); }
    @Test void purgedKeyReuse() throws Exception { purgedKeyReusedDifferentParamsIs40902SameParamsReplays(); }
    @Test void multiLineMovements() throws Exception { multiLineMovementsInsideIdemContextDoNotCollide(); }
}
