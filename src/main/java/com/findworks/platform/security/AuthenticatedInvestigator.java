package com.findworks.platform.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

public record AuthenticatedInvestigator(
        UUID userId,
        UUID membershipId,
        UUID organizationId,
        String organizationSlug,
        String organizationName,
        String displayName,
        String email,
        List<String> authorities
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
