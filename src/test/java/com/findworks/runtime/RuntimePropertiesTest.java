package com.findworks.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RuntimePropertiesTest {

    @Test
    void commandIsDigestPinnedLimitedAndDefaultDeny() {
        var command = properties("none", "", "findworks/pi@sha256:" + "a".repeat(64)).command("findworks-turn-1");

        assertThat(command).contains(
                "--read-only", "--read-only-tmpfs=false",
                "--tmpfs=/run/findworks:rw,noexec,nosuid,nodev,size=16m",
                "--cap-drop=ALL", "--security-opt=no-new-privileges",
                "--pids-limit=64", "--memory=512m", "--memory-swap=512m",
                "--cpus=1.0", "--ulimit=nofile=256:256", "--network=none", "--user=10001:10001")
                .doesNotContain("--privileged", "--network=host", "--volume", "--mount", "secret");
    }

    @Test
    void unsafeNetworkOrMutableImageFailsBeforeStartingAnEngine() {
        assertThatThrownBy(() -> properties("host", "", "findworks/pi@sha256:" + "a".repeat(64))
                .command("findworks-turn-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("network is unsafe");
        assertThatThrownBy(() -> properties("none", "", "findworks/pi:latest")
                .command("findworks-turn-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pinned");
        assertThatThrownBy(() -> properties("runtime-egress", "http://proxy.invalid",
                "findworks/pi@sha256:" + "a".repeat(64)).command("findworks-turn-1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS URI");
    }

    private static RuntimeProperties properties(String network, String proxy, String image) {
        return new RuntimeProperties(true, "podman", image, network, proxy, "10001:10001",
                "512m", "1.0", 64, 256, "16m", Duration.ofSeconds(90),
                "anthropic", "claude-test", "provider-secret", "test-key", "unused");
    }
}
