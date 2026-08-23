package com.findworks.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DiscoveryDraftTest {

    @Test
    void trimsValidInput() {
        assertThat(DiscoveryDraft.from(" Returns ", " Learn the real process "))
                .isEqualTo(new DiscoveryDraft("Returns", "Learn the real process"));
    }

    @Test
    void rejectsMissingObjective() {
        assertThatThrownBy(() -> DiscoveryDraft.from("Returns", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
