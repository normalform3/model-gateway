package com.modelgate.common.error;

public class ModelGateException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String requestId;

    public ModelGateException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public ModelGateException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ModelGateException(ErrorCode errorCode, String message, String requestId) {
        super(message);
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String requestId() {
        return requestId;
    }
}
