package com.turbolink.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局异常处理：把业务异常转成结构化 JSON 响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ShortLinkException.class)
    public ResponseEntity<Map<String, String>> handleShortLinkException(ShortLinkException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("error", e.getMessage()));
    }
}
