package com.ecom.common.util;

import java.util.regex.Pattern;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;

public final class IdempotencyKeys {

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_.:-]{8,100}$");

    private IdempotencyKeys() {
    }

    public static void validate(String key) {
        if (key == null || !VALID.matcher(key).matches()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key header must be 8-100 chars of letters, digits, '_', '-', '.', ':' (a UUID is ideal)");
        }
    }
}
