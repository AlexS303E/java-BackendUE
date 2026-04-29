package com.game.backend.common.api;

import org.springframework.http.HttpStatus;

/**
 * Единое доменное исключение для API: хранит HTTP-статус и машинный код ошибки.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
