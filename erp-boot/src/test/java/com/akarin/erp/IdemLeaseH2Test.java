package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** #22-9 租期（H2） */
@SpringBootTest(properties = "erp.idempotency.processing-lease-seconds=2")
@AutoConfigureMockMvc
class IdemLeaseH2Test extends IdemLeaseSupport {
    @Test void committedButProcessingReplays() throws Exception { committedButProcessingReplaysAfterLease(); }
    @Test void retryWhileLeaseValid() throws Exception { retryWhileLeaseValidIs40904(); }
    @Test void twoReclaimersOneDoc() throws Exception { twoReclaimersExactlyOneDocument(); }
    @Test void documentUniqueConstraint() throws Exception { documentUniqueConstraintBlocksSecondInsert(); }
    @Test void crashThenLeaseCreatesOne() throws Exception { crashThenLeaseExpiredCreatesOne(); }
    @Test void slowOriginalAndTakeover() throws Exception { slowOriginalAndTakeoverOneDocument(); }
}
