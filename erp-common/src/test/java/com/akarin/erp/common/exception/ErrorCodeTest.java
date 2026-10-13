package com.akarin.erp.common.exception;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {
    @Test void codesFollowConvention() {
        for (ErrorCode ec : ErrorCode.values()) {
            int expectedHttp = ec.getCode() >= 10000 ? ec.getCode() / 100 : ec.getCode();
            assertThat(ec.getHttpStatus()).as(ec.name()).isEqualTo(expectedHttp);
        }
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).distinct().count()).isEqualTo(ErrorCode.values().length);
        assertThat(ErrorCode.CODE_DUPLICATED.getCode()).isEqualTo(40001);
        assertThat(ErrorCode.CONCURRENT_MODIFICATION.getCode()).isEqualTo(40900);
        assertThat(ErrorCode.MASTER_DATA_REFERENCED.getCode()).isEqualTo(40901);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.getCode()).isEqualTo(40902);
    }

    @Test void bizExceptionCarriesErrorCode() {
        BizException plain = new BizException("x");
        assertThat(plain.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
        BizException e = new BizException(ErrorCode.MASTER_DATA_REFERENCED);
        assertThat(e.getCode()).isEqualTo(40901);
        assertThat(e.getHttpStatus()).isEqualTo(409);
        assertThat(e.getMessage()).isEqualTo(ErrorCode.MASTER_DATA_REFERENCED.getDefaultMsg());
    }
}
