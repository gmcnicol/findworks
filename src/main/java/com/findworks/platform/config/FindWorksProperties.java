package com.findworks.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "findworks")
public record FindWorksProperties(String pilotOrganizationSlug) {
}
