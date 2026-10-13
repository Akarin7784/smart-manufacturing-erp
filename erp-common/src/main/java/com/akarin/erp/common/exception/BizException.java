package com.akarin.erp.common.exception;

import lombok.Getter;

/** 业务异常，携带 {@link ErrorCode} */
@Getter
public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    /** 一般业务校验失败：HTTP 400 / code 400 */
    public BizException(String msg) { this(ErrorCode.BAD_REQUEST, msg); }
    public BizException(ErrorCode errorCode) { this(errorCode, errorCode.getDefaultMsg()); }
    public BizException(ErrorCode errorCode, String msg) { super(msg); this.errorCode = errorCode; }

    public int getCode() { return errorCode.getCode(); }
    public int getHttpStatus() { return errorCode.getHttpStatus(); }
}
