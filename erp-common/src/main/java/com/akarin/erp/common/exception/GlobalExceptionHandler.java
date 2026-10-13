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
        if (fe == null) return of(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.getDefaultMsg());
        String m = fe.getDefaultMessage() == null ? "" : fe.getDefaultMessage();
        // 约束上显式标记了 FullMessage 的提示已自带字段名，原样返回；其余（框架默认提示等）前面加字段名
        return of(ErrorCode.BAD_REQUEST, isFullMessage(fe) ? m : fe.getField() + " " + m);
    }

    static boolean isFullMessage(org.springframework.validation.FieldError fe) {
        if (!fe.contains(jakarta.validation.ConstraintViolation.class)) return false;
        var d = fe.unwrap(jakarta.validation.ConstraintViolation.class).getConstraintDescriptor();
        return d.getPayload().contains(com.akarin.erp.common.validation.FullMessage.class)
                || d.getAnnotation() instanceof com.akarin.erp.common.validation.MoneyScale;
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
