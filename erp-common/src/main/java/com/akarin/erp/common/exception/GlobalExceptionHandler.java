package com.akarin.erp.common.exception;

import com.akarin.erp.common.web.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** HTTP 状态 = ErrorCode.httpStatus（大类），body.code = ErrorCode.code（细分码），body 统一 {code,msg,data} */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    static ResponseEntity<R<Void>> of(ErrorCode ec, String msg) {
        return ResponseEntity.status(ec.getHttpStatus()).body(R.fail(ec.getCode(), msg));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> biz(BizException e) { return of(e.getErrorCode(), e.getMessage()); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> valid(MethodArgumentNotValidException e) {
        var fe = e.getBindingResult().getFieldError();
        return of(ErrorCode.BAD_REQUEST, fe == null ? ErrorCode.BAD_REQUEST.getDefaultMsg() : fe.getField() + " " + fe.getDefaultMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<R<Void>> badRequest(Exception e) { return of(ErrorCode.BAD_REQUEST, "请求格式错误"); }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<R<Void>> denied(AccessDeniedException e) { return of(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getDefaultMsg()); }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<R<Void>> notFound(Exception e) { return of(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getDefaultMsg()); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> method(HttpRequestMethodNotSupportedException e) {
        return of(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getDefaultMsg());
    }

    /** 唯一键冲突、数据库层并发冲突 → 40900 */
    @ExceptionHandler({DuplicateKeyException.class, ConcurrencyFailureException.class})
    public ResponseEntity<R<Void>> conflict(Exception e) {
        return of(ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.CONCURRENT_MODIFICATION.getDefaultMsg());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> other(Exception e) {
        log.error("unexpected", e);
        return of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMsg());
    }
}
