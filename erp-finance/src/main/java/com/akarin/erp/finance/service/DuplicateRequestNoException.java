package com.akarin.erp.finance.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;

/** 核销请求号撞唯一索引（并发同号提交）。本事务回滚后由调用方按幂等规则重放；未处理时兜底为 40900 */
public class DuplicateRequestNoException extends BizException {
    public DuplicateRequestNoException(String requestNo) {
        super(ErrorCode.CONCURRENT_MODIFICATION, "重复的核销请求（并发提交）: " + requestNo);
    }
}
