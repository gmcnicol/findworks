package com.findworks.acceptance;

import com.findworks.acceptance.AcceptanceRepository.LiveBinding;
import com.findworks.acceptance.AcceptanceRepository.ReleaseManifest;
import com.findworks.operations.CorrelationContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'acceptance'"
        + " && '${findworks.acceptance.run-command:false}' == 'true'")
final class AcceptanceCommand implements ApplicationRunner {

    private final AcceptanceRepository acceptance;
    private final CorrelationContext correlations;
    private final ConfigurableApplicationContext application;
    private final ObjectMapper json;
    private final String command;
    private final Path input;

    AcceptanceCommand(AcceptanceRepository acceptance, CorrelationContext correlations,
            ConfigurableApplicationContext application, ObjectMapper json,
            @Value("${findworks.acceptance.command:}") String command,
            @Value("${findworks.acceptance.input:}") Path input) {
        this.acceptance = acceptance;
        this.correlations = correlations;
        this.application = application;
        this.json = json;
        this.command = command;
        this.input = input;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        try (var ignored = correlations.open(UUID.randomUUID())) {
            switch (command) {
                case "record-scripted" -> recordScripted();
                case "open-live" -> openLive();
                case "verify-live" -> verifyLive();
                case "finalise" -> finaliseRun();
                default -> throw new IllegalArgumentException("Unknown acceptance command.");
            }
        } finally {
            application.close();
        }
    }

    private void recordScripted() throws Exception {
        var request = read(ScriptedInput.class);
        var recorded = acceptance.recordScripted(request.release(), request.restoreDrillId(),
                request.checks(), request.stories(), request.resultsDigest());
        System.out.println("m0_scripted run_id=" + recorded.id() + " outcome="
                + (recorded.passed() ? "passed" : "failed"));
        if (!recorded.passed()) throw new IllegalStateException("Scripted acceptance failed.");
    }

    private void openLive() throws Exception {
        var request = read(OpenInput.class);
        System.out.println("m0_live run_id="
                + acceptance.openLive(request.scriptedRunId(), request.restoreDrillId()));
    }

    private void verifyLive() throws Exception {
        var request = read(LiveBinding.class);
        var passed = acceptance.verifyLive(request);
        System.out.println("m0_live run_id=" + request.runId() + " verification="
                + (passed ? "passed" : "failed"));
        if (!passed) throw new IllegalStateException("Live acceptance verification failed.");
    }

    private void finaliseRun() throws Exception {
        var request = read(RunInput.class);
        var passed = acceptance.finalise(request.runId());
        System.out.println("m0_live run_id=" + request.runId() + " outcome="
                + (passed ? "passed" : "failed"));
        if (!passed) throw new IllegalStateException("Live acceptance failed.");
    }

    private <T> T read(Class<T> type) throws Exception {
        if (!Files.isRegularFile(input) || Files.size(input) <= 0 || Files.size(input) > 1024 * 1024) {
            throw new IllegalArgumentException("Acceptance input is invalid.");
        }
        return json.readValue(Files.readString(input), type);
    }

    record ScriptedInput(ReleaseManifest release, UUID restoreDrillId,
            Map<String, AcceptanceRepository.CheckEvidence> checks,
            java.util.List<AcceptanceRepository.StoryEvidence> stories,
            String resultsDigest) {}
    record OpenInput(UUID scriptedRunId, UUID restoreDrillId) {}
    record RunInput(UUID runId) {}
}
