package com.akarin.erp.common.exception;

import com.akarin.erp.common.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** HTTP 状态码与 body.code 保持一致（400/401/403/404/409/500），body 统一 {code,msg,data} */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    static ResponseEntity<R<Void>> of(int code, String msg) {
        HttpStatus st = HttpStatus.resolve(code);
        return ResponseEntity.status(st == null ? HttpStatus.BAD_REQUEST : st).body(R.fail(code, msg));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> biz(BizException e) { return of(e.getCode(), e.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> valid(MethodArgumentNotValidException e) {
        var fe = e.getBindingResult().getFieldError();
        return of(400, fe == null ? "参数错误" : fe.getField() + " " + fe.getDefaultMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<R<Void>> badRequest(Exception e) { return of(400, "请求格式错误"); }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> denied(AccessDeniedException e) { return of(403, "无权限"); }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> notFound(NoResourceFoundException e) { return of(404, "资源不存在"); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> method(HttpRequestMethodNotSupportedException e) { return of(405, "请求方法不支持"); }

    /** 唯一键冲突、数据库层并发冲突统一视为 409 */
    @ExceptionHandler({DuplicateKeyException.class, ConcurrencyFailureException.class})
    public ResponseEntity<R<Void>> conflict(Exception e) { return of(409, "数据冲突，请重试"); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> other(Exception e) { log.error("unexpected", e); return of(500, "系统异常"); }
}
