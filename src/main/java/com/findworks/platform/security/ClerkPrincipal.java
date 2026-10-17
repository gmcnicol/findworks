package com.findworks.platform.security;

import java.security.Principal;

/** Authentication identity shared by both roles; it does not itself grant product permissions. */
public record ClerkPrincipal(String subject) implements Principal {
    @Override public String getName() { return subject; }
}
