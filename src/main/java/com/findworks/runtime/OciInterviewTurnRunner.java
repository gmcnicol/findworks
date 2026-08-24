package com.findworks.runtime;

import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public final class OciInterviewTurnRunner implements InterviewTurnRunner, FindingsExtractionRunner {

    private final ObjectMapper json;
    private final RuntimeProperties properties;

    public OciInterviewTurnRunner(ObjectMapper json, RuntimeProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    @Override
    public String runtimeVersion() {
        return properties.runtimeVersion();
    }

    @Override
    public InterviewTurnRunner.Result run(InterviewTurnRunner.Request request) throws RuntimeFailure {
        return result(jsonEnvelope(request.context().runId(), input(request), Envelope.class));
    }

    @Override
    public FindingsExtractionRunner.Result run(FindingsExtractionRunner.Request request) throws RuntimeFailure {
        var envelope = jsonEnvelope(request.context().runId(), input(request), FindingsEnvelope.class);
        if ("submitted".equals(envelope.status()) && envelope.submission() != null) {
            return new FindingsExtractionRunner.Result(envelope.submission(), properties.runtimeVersion(),
                    envelope.modelAttempts(), envelope.findWorksCredential());
        }
        throw failure(envelope.failureClass(), envelope.modelAttempts(), null);
    }

    private <T> T jsonEnvelope(java.util.UUID runId, Map<String, Object> input, Class<T> type)
            throws RuntimeFailure {
        try {
            requireRootless();
        } catch (RuntimeFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new RuntimeFailure(RuntimeFailure.Kind.UNAVAILABLE, 0);
        }
        var containerName = "findworks-turn-" + runId;
        Process process = null;
        var reader = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        try {
            var builder = new ProcessBuilder(properties.command(containerName))
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            keepOnlyEngineEnvironment(builder.environment());
            process = builder.start();
            var running = process;
            var output = reader.submit(() -> readEnvelope(running.inputReader(StandardCharsets.UTF_8)));
            try (var writer = process.outputWriter(StandardCharsets.UTF_8)) {
                writer.write(json.writeValueAsString(input));
                writer.newLine();
            }
            if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new RuntimeFailure(RuntimeFailure.Kind.TIMEOUT, 0);
            }
            if (process.exitValue() != 0) {
                throw new RuntimeFailure(RuntimeFailure.Kind.PROCESS_DIED, 0);
            }
            return json.readValue(output.get(2, TimeUnit.SECONDS), type);
        } catch (RuntimeFailure failure) {
            throw failure;
        } catch (Exception error) {
            throw new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            reader.shutdownNow();
            remove(containerName);
        }
    }

    private Map<String, Object> input(InterviewTurnRunner.Request request) {
        var input = new LinkedHashMap<String, Object>();
        input.put("jobKind", "interview_turn");
        input.put("context", request.context());
        input.put("checkpoint", request.checkpoint() == null ? null
                : Base64.getEncoder().encodeToString(request.checkpoint()));
        input.put("findWorksCredential", request.credential());
        input.put("credentialExpiresAt", request.credentialExpiresAt());
        input.put("provider", properties.provider());
        input.put("model", properties.model());
        input.put("providerCredential", properties.providerCredential());
        input.put("egressProxy", properties.proxyOrNull());
        return input;
    }

    private Map<String, Object> input(FindingsExtractionRunner.Request request) {
        var input = new LinkedHashMap<String, Object>();
        input.put("jobKind", "findings_extraction");
        input.put("context", request.context());
        input.put("checkpoint", null);
        input.put("findWorksCredential", request.credential());
        input.put("credentialExpiresAt", request.credentialExpiresAt());
        input.put("provider", properties.provider());
        input.put("model", properties.model());
        input.put("providerCredential", properties.providerCredential());
        input.put("egressProxy", properties.proxyOrNull());
        return input;
    }

    private InterviewTurnRunner.Result result(Envelope envelope) throws RuntimeFailure {
        var checkpoint = decodeCheckpoint(envelope.checkpoint());
        if ("submitted".equals(envelope.status()) && envelope.submission() != null) {
            return new InterviewTurnRunner.Result(envelope.submission(), checkpoint, properties.runtimeVersion(),
                    envelope.modelAttempts(), envelope.findWorksCredential());
        }
        throw failure(envelope.failureClass(), envelope.modelAttempts(), checkpoint);
    }

    private RuntimeFailure failure(String failureClass, int modelAttempts, byte[] checkpoint)
            throws RuntimeFailure {
        var kind = switch (String.valueOf(failureClass)) {
            case "transient_model_failure" -> RuntimeFailure.Kind.TRANSIENT_MODEL;
            case "process_died" -> RuntimeFailure.Kind.PROCESS_DIED;
            case "runtime_timeout" -> RuntimeFailure.Kind.TIMEOUT;
            case "runtime_unavailable" -> RuntimeFailure.Kind.UNAVAILABLE;
            default -> RuntimeFailure.Kind.INVALID_OUTPUT;
        };
        return new RuntimeFailure(kind, boundedAttempts(modelAttempts), checkpoint);
    }

    private static int boundedAttempts(int attempts) throws RuntimeFailure {
        if (attempts < 0 || attempts > 3) {
            throw new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0);
        }
        return attempts;
    }

    private static byte[] decodeCheckpoint(String encoded) throws RuntimeFailure {
        if (encoded == null) {
            return null;
        }
        if (encoded.length() > 14_000_000) {
            throw new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0);
        }
        try {
            var checkpoint = Base64.getDecoder().decode(encoded);
            if (checkpoint.length == 0 || checkpoint.length > 10_000_000) {
                throw new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0);
            }
            return checkpoint;
        } catch (IllegalArgumentException error) {
            throw new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0);
        }
    }

    private String readEnvelope(BufferedReader output) throws Exception {
        var line = output.readLine();
        if (line == null || line.length() > 15_000_000 || output.readLine() != null) {
            throw new IllegalStateException("Runtime returned an invalid envelope.");
        }
        return line;
    }

    private void requireRootless() throws Exception {
        var builder = new ProcessBuilder(properties.rootlessCheckCommand())
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        keepOnlyEngineEnvironment(builder.environment());
        var process = builder.start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new RuntimeFailure(RuntimeFailure.Kind.UNAVAILABLE, 0);
        }
        var value = new String(process.getInputStream().readNBytes(32), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || !"true".equals(value)) {
            throw new RuntimeFailure(RuntimeFailure.Kind.UNAVAILABLE, 0);
        }
    }

    private void remove(String containerName) {
        try {
            var builder = new ProcessBuilder(properties.removeCommand(containerName))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            keepOnlyEngineEnvironment(builder.environment());
            var cleanup = builder.start();
            if (!cleanup.waitFor(5, TimeUnit.SECONDS)) {
                cleanup.destroyForcibly();
            }
        } catch (Exception ignored) {
            // The exact container also has --rm. Issue #32 adds host-level orphan monitoring.
        }
    }

    private static void keepOnlyEngineEnvironment(Map<String, String> environment) {
        var inherited = Map.copyOf(environment);
        environment.clear();
        for (var name : List.of("PATH", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "CONTAINER_HOST")) {
            if (inherited.containsKey(name)) {
                environment.put(name, inherited.get(name));
            }
        }
    }

    private record Envelope(String status, com.findworks.interview.InterviewRuntimeRepository.Submission submission,
            String checkpoint, int modelAttempts, String failureClass, String findWorksCredential) {}
    private record FindingsEnvelope(String status, com.findworks.interview.FindingsRepository.Submission submission,
            String checkpoint, int modelAttempts, String failureClass, String findWorksCredential) {}
}
