package com.akarin.erp.finance.controller;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.finance.dto.SettleReq;
import com.akarin.erp.finance.entity.Settlement;
import com.akarin.erp.finance.mapper.PaymentMapper;
import com.akarin.erp.finance.mapper.SettlementMapper;
import com.akarin.erp.finance.service.DuplicateRequestNoException;
import com.akarin.erp.finance.service.FinanceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 请求号唯一索引冲突走幂等重放：参数相同返回原结果，不同返回 40902 */
class SettlementControllerTest {
    FinanceService service = mock(FinanceService.class);
    SettlementController c = new SettlementController(service, mock(PaymentMapper.class), mock(SettlementMapper.class));
    SettleReq req = new SettleReq("R1", 1L, 2L, BigDecimal.ONE);

    @Test void duplicateRequestNoSameParamsReturnsOriginal() {
        Settlement done = new Settlement();
        when(service.settle(req)).thenThrow(new DuplicateRequestNoException("R1"));
        when(service.replaySettlement(req)).thenReturn(done);
        assertThat(c.settle(req).getData()).isSameAs(done);
    }

    @Test void duplicateRequestNoDifferentParams40902() {
        when(service.settle(req)).thenThrow(new DuplicateRequestNoException("R1"));
        when(service.replaySettlement(req)).thenThrow(new BizException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
        assertThatThrownBy(() -> c.settle(req)).extracting("errorCode").isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
