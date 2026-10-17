package com.ecom.user;

/** What a JWT must still match to be honoured: the account is enabled and the token version is current. */
public record TokenState(boolean enabled, int tokenVersion) {
}
