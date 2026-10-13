package com.akarin.erp.common.exception;

import com.akarin.erp.common.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public R<Void> biz(BizException e) { return R.fail(e.getCode(), e.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> valid(MethodArgumentNotValidException e) {
        var fe = e.getBindingResult().getFieldError();
        return R.fail(400, fe == null ? "参数错误" : fe.getField() + " " + fe.getDefaultMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public R<Void> denied(AccessDeniedException e) { return R.fail(403, "无权限"); }

    @ExceptionHandler(Exception.class)
    public R<Void> other(Exception e) { log.error("unexpected", e); return R.fail(500, "系统异常"); }
}
