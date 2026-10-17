package com.ecom.user;

import java.nio.charset.StandardCharsets;

import com.ecom.common.error.ApiException;
import com.ecom.common.error.ErrorCode;

/** NIST 800-63B style: length over composition rules. BCrypt only uses the first 72 bytes, so cap there. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password, String email) {
        if (password == null || password.isBlank() || password.length() < MIN_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (!fitsBcrypt(password)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must be at most " + MAX_BYTES + " bytes");
        }
        if (email != null && password.equalsIgnoreCase(email)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Password must not be the same as the email");
        }
    }

    public static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
}
