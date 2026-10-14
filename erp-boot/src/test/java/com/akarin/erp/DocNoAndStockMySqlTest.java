package com.akarin.erp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** #18 a / b / e（MySQL，-Pmysql-it） */
@org.junit.jupiter.api.Tag("mysql")
@org.springframework.test.context.ActiveProfiles("mysql")
@SpringBootTest
@AutoConfigureMockMvc
class DocNoAndStockMySqlTest extends DocNoAndStockSupport {
    @Test void docNoTwoInstances() throws Exception { docNoTwoInstancesNoDuplicates(); }
    @Test void movementPrefix() throws Exception { movementPrefixDistinctFromSalesOrder(); }
    @Test void softDeletedStockRevived() throws Exception { softDeletedStockRowIsRevivedOnStockIn(); }
}
