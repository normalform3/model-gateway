package com.modelgate.common.api;

public record ErrorResponse(ErrorBody error) {
    public record ErrorBody(String code, String message, String requestId, boolean retryable) {
    }
}
