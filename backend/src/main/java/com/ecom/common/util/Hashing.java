package com.ecom.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Hashing {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // no 0/O/1/I

    private Hashing() {
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Cryptographically random, URL-safe token. 32 bytes = 256 bits of entropy. */
    public static String randomUrlToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public static String randomAlphanumeric(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHANUMERIC[RANDOM.nextInt(ALPHANUMERIC.length)];
        }
        return new String(out);
    }
}
