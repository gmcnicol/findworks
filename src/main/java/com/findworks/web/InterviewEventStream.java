package com.findworks.web;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class InterviewEventStream {
    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1_000L;
    private static final String READY_TRIGGER = """
            elements <div id="interview-state-trigger" data-init="@get('/interview/state')"></div>
            """.strip();

    private final ConcurrentHashMap<SseEmitter, String> statePaths = new ConcurrentHashMap<>();
    private final DataSource dataSource;
    private final boolean listenForNotifications;
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile Connection listenerConnection;
    private Thread listenerThread;

    InterviewEventStream(DataSource dataSource,
                         @Value("${findworks.process-mode:web}") String processMode) {
        this.dataSource = dataSource;
        this.listenForNotifications = processMode.equals("web") || processMode.equals("all");
    }

    @PostConstruct
    void start() {
        if (listenForNotifications) listenerThread = Thread.startVirtualThread(this::listen);
    }

    SseEmitter subscribe(UUID sessionId) { return subscribe(sessionId, "/interview/state"); }

    SseEmitter subscribe(UUID sessionId, String statePath) {
        var emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        statePaths.put(emitter, statePath);
        subscribers.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(sessionId, emitter));
        emitter.onTimeout(() -> remove(sessionId, emitter));
        emitter.onError(ignored -> remove(sessionId, emitter));
        return emitter;
    }

    void signal(UUID sessionId) {
        var emitters = subscribers.remove(sessionId);
        if (emitters == null) return;
        emitters.forEach(this::signal);
    }

    void signal(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("datastar-patch-elements")
                    .data(READY_TRIGGER.replace("/interview/state", statePaths.getOrDefault(emitter, "/interview/state"))));
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
    }

    private void remove(UUID sessionId, SseEmitter emitter) {
        statePaths.remove(emitter);
        subscribers.computeIfPresent(sessionId, (ignored, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }

    private void listen() {
        while (running) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                listenerConnection = connection;
                statement.execute("listen interview_session_changes");
                var postgres = connection.unwrap(PGConnection.class);
                while (running && !connection.isClosed()) {
                    var notifications = postgres.getNotifications(10_000);
                    if (notifications == null) continue;
                    for (var notification : notifications) {
                        try {
                            signal(UUID.fromString(notification.getParameter()));
                        } catch (IllegalArgumentException ignored) {
                            // The database trigger emits UUIDs; ignore malformed external notifications.
                        }
                    }
                }
            } catch (Exception exception) {
                if (running) {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                listenerConnection = null;
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        var connection = listenerConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // Closing the listener connection is best effort during shutdown.
            }
        }
        if (listenerThread != null) listenerThread.interrupt();
        subscribers.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
        subscribers.clear();
        statePaths.clear();
    }
}
