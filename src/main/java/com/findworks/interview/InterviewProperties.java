package com.findworks.interview;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.interview")
record InterviewProperties(String contact) {

    String contactOr(String investigatorEmail) {
        if (contact == null || contact.isBlank()) {
            return investigatorEmail;
        }
        var value = contact.trim();
        if (value.length() > 1000 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Interview contact route is invalid.");
        }
        return value;
    }
}
