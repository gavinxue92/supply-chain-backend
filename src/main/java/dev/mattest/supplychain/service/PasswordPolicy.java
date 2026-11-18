package dev.mattest.supplychain.service;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private static final int BCRYPT_MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static boolean isBcryptCompatible(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
    }
}
