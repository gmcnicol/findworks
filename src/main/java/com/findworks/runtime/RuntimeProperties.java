package com.findworks.runtime;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.runtime")
public record RuntimeProperties(
        boolean enabled,
        String executable,
        String image,
        String network,
        String egressProxy,
        String user,
        String memory,
        String cpus,
        int pidsLimit,
        int nofileLimit,
        String tmpfsSize,
        Duration timeout,
        String provider,
        String model,
        String providerCredential,
        String checkpointKeyId,
        String checkpointKey) {

    private static final Pattern IMAGE = Pattern.compile("^[^\\s]+@sha256:[a-f0-9]{64}$");
    private static final Pattern NAME = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}$");
    private static final Pattern USER = Pattern.compile("^[1-9][0-9]*:[1-9][0-9]*$");
    private static final Pattern MEMORY = Pattern.compile("^[1-9][0-9]*(?:[kKmMgG])$");
    private static final Pattern CPU = Pattern.compile("^(?:0\\.[1-9][0-9]*|[1-9][0-9]*(?:\\.[0-9]+)?)$");

    public List<String> command(String containerName) {
        requireEnabled();
        if (!NAME.matcher(containerName).matches()) {
            throw new IllegalStateException("Runtime container name is invalid.");
        }
        var effectiveNetwork = required(network, "Runtime network is not configured.");
        if (!("none".equals(effectiveNetwork) || NAME.matcher(effectiveNetwork).matches())
                || "host".equals(effectiveNetwork)) {
            throw new IllegalStateException("Runtime network is unsafe.");
        }
        if (!"none".equals(effectiveNetwork)) {
            var proxy = URI.create(required(egressProxy, "Runtime egress proxy is required when networking is enabled."));
            if (!"https".equals(proxy.getScheme()) || proxy.getHost() == null || proxy.getUserInfo() != null
                    || proxy.getQuery() != null || proxy.getFragment() != null) {
                throw new IllegalStateException("Runtime egress proxy must be a credential-free HTTPS URI.");
            }
        }
        if (!IMAGE.matcher(required(image, "Runtime image is not configured.")).matches() || image.length() > 100) {
            throw new IllegalStateException("Runtime image must be pinned by SHA-256 digest.");
        }
        if (!USER.matcher(required(user, "Runtime user is not configured.")).matches()) {
            throw new IllegalStateException("Runtime user must be a non-root numeric user and group.");
        }
        if (!MEMORY.matcher(required(memory, "Runtime memory limit is not configured.")).matches()
                || !MEMORY.matcher(required(tmpfsSize, "Runtime tmpfs limit is not configured.")).matches()
                || !CPU.matcher(required(cpus, "Runtime CPU limit is not configured.")).matches()
                || pidsLimit < 2 || pidsLimit > 1024 || nofileLimit < 32 || nofileLimit > 4096) {
            throw new IllegalStateException("Runtime resource limits are invalid.");
        }
        if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("Runtime wall timeout must be between one second and five minutes.");
        }
        required(provider, "Runtime provider is not configured.");
        required(model, "Runtime model is not configured.");
        required(providerCredential, "Runtime provider credential is not configured.");

        return List.of(required(executable, "OCI executable is not configured."), "run", "--rm", "--interactive",
                "--name=" + containerName,
                "--read-only", "--read-only-tmpfs=false",
                "--tmpfs=/run/findworks:rw,noexec,nosuid,nodev,size=" + tmpfsSize,
                "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--pids-limit=" + pidsLimit, "--memory=" + memory, "--memory-swap=" + memory,
                "--cpus=" + cpus, "--ulimit=nofile=" + nofileLimit + ":" + nofileLimit,
                "--network=" + effectiveNetwork, "--user=" + user, image);
    }

    public List<String> rootlessCheckCommand() {
        requireEnabled();
        return List.of(required(executable, "OCI executable is not configured."), "info", "--format",
                "{{.Host.Security.Rootless}}");
    }

    public List<String> removeCommand(String containerName) {
        if (!NAME.matcher(containerName).matches()) {
            throw new IllegalStateException("Runtime container name is invalid.");
        }
        return List.of(required(executable, "OCI executable is not configured."), "rm", "--force", containerName);
    }

    public String runtimeVersion() {
        requireEnabled();
        return required(image, "Runtime image is not configured.");
    }

    public String proxyOrNull() {
        return "none".equals(network) ? null : egressProxy;
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException("Isolated Interview runtime is disabled.");
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank() || value.length() > 500 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
