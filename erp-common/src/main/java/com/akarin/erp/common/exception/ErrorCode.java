package com.akarin.erp.common.exception;

import lombok.Getter;

/**
 * 错误码：HTTP 状态表示大类，body.code 表示细分码（与前端约定）。
 * 通用错误的 code 等于 HTTP 状态码；业务细分码 = HTTP 状态 × 100 + 序号。
 */
@Getter
public enum ErrorCode {
    BAD_REQUEST(400, 400, "请求参数错误"),
    CODE_DUPLICATED(400, 40001, "编码已存在"),
    UNAUTHORIZED(401, 401, "未登录或登录已失效"),
    FORBIDDEN(403, 403, "无权限"),
    NOT_FOUND(404, 404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, 405, "请求方法不支持"),
    CONCURRENT_MODIFICATION(409, 40900, "数据已被修改，请刷新后重试"),
    MASTER_DATA_REFERENCED(409, 40901, "主数据已被单据引用，不能删除，只能停用"),
    IDEMPOTENCY_KEY_REUSED(409, 40902, "请求号已被使用且参数不一致"),
    ORDER_STATUS_CHANGED(409, 40903, "单据状态已变更，请刷新后重试"),
    INTERNAL_ERROR(500, 500, "系统异常");

    private final int httpStatus;
    private final int code;
    private final String defaultMsg;

    ErrorCode(int httpStatus, int code, String defaultMsg) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMsg = defaultMsg;
    }
}
