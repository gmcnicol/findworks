package com.findworks.discovery;

record DiscoveryDraft(String title, String objective) {

    static DiscoveryDraft from(String title, String objective) {
        if (title == null || objective == null || title.isBlank() || objective.isBlank()
                || title.length() > 200 || objective.length() > 5_000) {
            throw new IllegalArgumentException("Add a title and describe what you need to learn.");
        }
        return new DiscoveryDraft(title.trim(), objective.trim());
    }
}
