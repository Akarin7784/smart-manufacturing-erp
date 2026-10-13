package com.akarin.erp.common.web;

import lombok.Data;

/** 统一响应体 */
@Data
public class R<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok(T data) { R<T> r = new R<>(); r.code = 0; r.msg = "ok"; r.data = data; return r; }
    public static <T> R<T> ok() { return ok(null); }
    public static <T> R<T> fail(int code, String msg) { R<T> r = new R<>(); r.code = code; r.msg = msg; return r; }
}
