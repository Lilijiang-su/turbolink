package com.turbolink.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 短链业务异常，携带 HTTP 状态码。
 * Controller 捕获后转为对应响应，避免散落 try-catch。
 */
@Getter
public class ShortLinkException extends RuntimeException {

    private final HttpStatus status;

    public ShortLinkException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** 域名被拉黑 */
    public static ShortLinkException blockedDomain(String domain) {
        return new ShortLinkException(HttpStatus.FORBIDDEN, "域名已被拉黑: " + domain);
    }

    /** 自定义短码格式非法 */
    public static ShortLinkException invalidCustomCode(String code) {
        return new ShortLinkException(HttpStatus.BAD_REQUEST, "自定义短码格式非法: " + code);
    }

    /** 自定义短码已被占用 */
    public static ShortLinkException codeOccupied(String code) {
        return new ShortLinkException(HttpStatus.CONFLICT, "自定义短码已被占用: " + code);
    }
}
