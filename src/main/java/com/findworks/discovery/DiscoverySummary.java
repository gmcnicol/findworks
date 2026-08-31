package com.findworks.discovery;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DiscoverySummary(
        UUID id,
        String title,
        String objective,
        OffsetDateTime createdAt
) {
}
