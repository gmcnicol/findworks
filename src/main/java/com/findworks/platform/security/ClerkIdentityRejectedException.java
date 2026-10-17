package com.findworks.platform.security;

import org.springframework.security.authentication.BadCredentialsException;

final class ClerkIdentityRejectedException extends BadCredentialsException {
    ClerkIdentityRejectedException() {
        super("A valid Clerk identity with a verified primary email is required");
    }
}
