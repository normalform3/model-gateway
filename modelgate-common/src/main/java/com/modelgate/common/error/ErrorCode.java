package com.modelgate.common.error;

public enum ErrorCode {
    INVALID_API_KEY(401, false, "Invalid API key."),
    API_KEY_DISABLED(403, false, "API key is disabled."),
    API_KEY_EXPIRED(403, false, "API key is expired."),
    MODEL_NOT_ALLOWED(403, false, "The API key is not allowed to use this model."),
    RATE_LIMIT_EXCEEDED(429, true, "The request exceeded the configured rate limit."),
    CONCURRENCY_LIMIT_EXCEEDED(429, true, "The request exceeded the configured concurrency limit."),
    QUOTA_INSUFFICIENT(402, false, "The quota account does not have enough available tokens."),
    MODEL_ROUTE_NOT_FOUND(404, false, "No available route target was found for the requested model."),
    PROVIDER_TIMEOUT(504, true, "The provider request timed out."),
    PROVIDER_UNAVAILABLE(503, true, "The provider is temporarily unavailable."),
    BAD_MODEL_REQUEST(400, false, "The model request is invalid."),
    IDEMPOTENCY_CONFLICT(409, false, "The idempotency key is already used by another request."),
    INTERNAL_ERROR(500, true, "Internal server error.");

    private final int httpStatus;
    private final boolean retryable;
    private final String defaultMessage;

    ErrorCode(int httpStatus, boolean retryable, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
