package com.akarin.erp.common.exception;

import lombok.Getter;

/** 业务异常 */
@Getter
public class BizException extends RuntimeException {
    private final int code;
    public BizException(String msg) { this(400, msg); }
    public BizException(int code, String msg) { super(msg); this.code = code; }
}
