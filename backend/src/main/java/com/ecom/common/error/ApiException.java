package com.ecom.common.error;

import java.util.Map;

public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " not found");
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
