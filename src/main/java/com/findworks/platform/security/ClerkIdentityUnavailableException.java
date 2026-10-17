package com.findworks.platform.security;

public final class ClerkIdentityUnavailableException extends RuntimeException {
    public ClerkIdentityUnavailableException() {
        super("Identity verification is temporarily unavailable");
    }
}
