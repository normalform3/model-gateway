package com.modelgate.common.error;

public class ModelGateException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String requestId;
    private final String limitDimension;

    public ModelGateException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public ModelGateException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public ModelGateException(ErrorCode errorCode, String message, String requestId) {
        this(errorCode, message, requestId, null);
    }

    public ModelGateException(ErrorCode errorCode, String message, String requestId, String limitDimension) {
        super(message);
        this.errorCode = errorCode;
        this.requestId = requestId;
        this.limitDimension = limitDimension;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String requestId() {
        return requestId;
    }

    public String limitDimension() {
        return limitDimension;
    }
}
