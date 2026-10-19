package com.findworks.web;

import java.time.Duration;

final class InvitationPolicy {
    static final Duration VALIDITY = Duration.ofDays(7);
    static final int VALIDITY_DAYS = Math.toIntExact(VALIDITY.toDays());
    static final long VALIDITY_SECONDS = VALIDITY.toSeconds();

    private InvitationPolicy() {}
}
