package com.modelgate.bootstrap.api;

import com.modelgate.common.api.ErrorResponse;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ModelGateException.class)
    public ResponseEntity<ErrorResponse> modelGate(ModelGateException ex) {
        ErrorCode code = ex.errorCode();
        return ResponseEntity.status(code.httpStatus()).body(toResponse(code, ex.getMessage(), ex.requestId()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> validation(WebExchangeBindException ex) {
        return ResponseEntity.badRequest().body(toResponse(ErrorCode.BAD_MODEL_REQUEST, "Request validation failed.", null));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ErrorResponse> internal(Throwable ex) {
        log.warn("Unhandled request failure.", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(toResponse(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(), null));
    }

    private ErrorResponse toResponse(ErrorCode code, String message, String requestId) {
        return new ErrorResponse(new ErrorResponse.ErrorBody(code.name(), message, requestId, code.retryable()));
    }
}
