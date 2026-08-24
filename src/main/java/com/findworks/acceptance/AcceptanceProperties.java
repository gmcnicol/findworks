package com.findworks.acceptance;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.acceptance")
public record AcceptanceProperties(String releaseDigest, String gitCommit,
        String skillDigest, String extensionDigest, String traceabilityDigest) {}
