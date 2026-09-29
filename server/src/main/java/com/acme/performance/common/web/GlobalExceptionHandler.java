package com.acme.performance.common.web;

import com.acme.performance.common.api.ApiResponse;
import com.acme.performance.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.ServletRequestBindingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    org.springframework.http.ResponseEntity<ApiResponse<Void>> api(ApiException ex, HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(ex.status())
                .body(ApiResponse.failure(ex.code(), ex.getMessage(), requestId(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return ApiResponse.failure("VALIDATION_ERROR", message, requestId(request));
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> binding(ServletRequestBindingException ex, HttpServletRequest request) {
        return ApiResponse.failure("INVALID_REQUEST", "请求头或参数不完整", requestId(request));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled API exception, requestId={}", requestId(request), ex);
        return ApiResponse.failure("INTERNAL_ERROR", "系统暂时不可用", requestId(request));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> malformed(Exception ex, HttpServletRequest request) {
        return ApiResponse.failure("INVALID_REQUEST", "请求格式或参数类型不正确", requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute(RequestIdFilter.ATTRIBUTE));
    }
}
