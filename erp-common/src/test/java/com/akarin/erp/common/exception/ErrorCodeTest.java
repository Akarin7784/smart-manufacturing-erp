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
        assertThat(ErrorCode.CONCURRENT_MODIFICATION.getDefaultMsg()).isEqualTo("数据已被修改，请刷新后重试");
        var r = new GlobalExceptionHandler().conflict(new org.springframework.dao.OptimisticLockingFailureException("x"));
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().getCode()).isEqualTo(40900);
        assertThat(r.getBody().getMsg()).as("全局兜底用通用文案").isEqualTo("数据已被修改，请刷新后重试");
        var d = new GlobalExceptionHandler().conflict(new org.springframework.dao.DuplicateKeyException("uk"));
        assertThat(d.getBody().getCode()).as("未被业务捕获的唯一键冲突仍兜底 40900").isEqualTo(40900);
        assertThat(ErrorCode.MASTER_DATA_REFERENCED.getCode()).isEqualTo(40901);
        assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.getCode()).isEqualTo(40902);
        assertThat(ErrorCode.ORDER_STATUS_CHANGED.getCode()).isEqualTo(40903);
        assertThat(ErrorCode.ORDER_STATUS_CHANGED.getHttpStatus()).isEqualTo(409);
        assertThat(ErrorCode.ORDER_STATUS_CHANGED.getDefaultMsg()).isEqualTo("单据状态已变更，请刷新后重试");
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
