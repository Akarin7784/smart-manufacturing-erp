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
    /** 业务异常：按 ErrorCode 映射，如 40001、40900、40901、40902、40903（单据状态已变更 → HTTP 409） */
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

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<R<Void>> badRequest(Exception e) { return of(ErrorCode.BAD_REQUEST, "请求格式错误"); }

    /** 请求体无法解析。字段类型不对（如金额传了 "abc"）时提示带上字段名 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> unreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException mi && !mi.getPath().isEmpty()) {
            String field = fieldPath(mi.getPath());
            Class<?> t = mi.getTargetType();
            String what = t != null && (Number.class.isAssignableFrom(t) || t.isPrimitive() && t != boolean.class) ? "必须是数字" : "格式不正确";
            return of(ErrorCode.BAD_REQUEST, field + " " + what);
        }
        return of(ErrorCode.BAD_REQUEST, "请求格式错误");
    }

    static String fieldPath(java.util.List<com.fasterxml.jackson.databind.JsonMappingException.Reference> path) {
        StringBuilder sb = new StringBuilder();
        for (var r : path) {
            if (r.getFieldName() != null) sb.append(sb.length() == 0 ? "" : ".").append(r.getFieldName());
            else if (r.getIndex() >= 0) sb.append('[').append(r.getIndex()).append(']');
        }
        return sb.toString();
    }

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

    /** 未归类的 SQL 异常：保持 500；日志只记异常类、SQLState、错误码，不记 SQL 与参数（异常消息里带 SQL） */
    @ExceptionHandler(org.springframework.jdbc.UncategorizedSQLException.class)
    public ResponseEntity<R<Void>> uncategorizedSql(org.springframework.jdbc.UncategorizedSQLException e) {
        java.sql.SQLException s = e.getSQLException();
        log.error("uncategorized SQL error: exception={} sqlState={} errorCode={}", e.getClass().getName(),
                s == null ? null : s.getSQLState(), s == null ? null : s.getErrorCode());
        return of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMsg());
    }

    /** 回滚/提交本身失败且没有可用的原始异常（TxRetry 已判定不是锁冲突）：500，简短 ERROR，不带 SQL 与堆栈 */
    @ExceptionHandler(org.springframework.transaction.TransactionSystemException.class)
    public ResponseEntity<R<Void>> txSystem(org.springframework.transaction.TransactionSystemException e) {
        java.sql.SQLException s = null;
        for (Throwable t = e; t != null && s == null; t = t.getCause() == t ? null : t.getCause())
            if (t instanceof java.sql.SQLException x) s = x;
        log.error("transaction system error: exception={} sqlState={} errorCode={}", e.getClass().getName(),
                s == null ? null : s.getSQLState(), s == null ? null : s.getErrorCode());
        return of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMsg());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> other(Exception e) {
        log.error("unexpected", e);
        return of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMsg());
    }
}
