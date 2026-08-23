package com.findworks.shaping;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShapingView(UUID sessionId, List<Message> messages, String status, Event event) {

    public static ShapingView empty() {
        return new ShapingView(null, List.of(), "ready", null);
    }

    public record Message(String author, String content, Instant createdAt) {}

    public record Event(String type, String eventId, String question) {}
}
