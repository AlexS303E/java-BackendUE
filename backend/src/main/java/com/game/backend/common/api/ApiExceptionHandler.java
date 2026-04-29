package com.game.backend.common.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setTitle(exception.code());
        detail.setProperty("code", exception.code());
        return detail;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        detail.setTitle("VALIDATION_ERROR");
        List<Map<String, String>> fields = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(this::fieldError)
            .toList();
        detail.setProperty("code", "VALIDATION_ERROR");
        detail.setProperty("fields", fields);
        return detail;
    }

    private Map<String, String> fieldError(FieldError error) {
        return Map.of(
            "field", error.getField(),
            "message", error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()
        );
    }
}
