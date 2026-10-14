package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** #18 a / b / e（H2） */
@SpringBootTest
@AutoConfigureMockMvc
class DocNoAndStockH2Test extends DocNoAndStockSupport {
    @Test void docNoTwoInstances() throws Exception { docNoTwoInstancesNoDuplicates(); }
    @Test void movementPrefix() throws Exception { movementPrefixDistinctFromSalesOrder(); }
    @Test void softDeletedStockRevived() throws Exception { softDeletedStockRowIsRevivedOnStockIn(); }
}
