package com.findworks.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FindingsStatusProjectorTest {
    private final FindingsStatusProjector projector = new FindingsStatusProjector();

    @Test
    void every_session_extraction_and_package_state_is_presented_without_being_mislabelled_or_hidden() {
        assertThat(projector.project("ACTIVE", "PENDING", null).label())
                .isEqualTo("Interview in progress · transcript saved");
        assertThat(projector.project("RUNTIME_FAILED", "PENDING", null).label())
                .isEqualTo("Interview needs recovery · transcript saved");
        assertThat(projector.project("UNEXPECTED", "PENDING", null).label())
                .isEqualTo("Interview status needs attention · transcript saved");
        assertThat(projector.project("PENDING", null))
                .isEqualTo(new FindingsStatusProjector.Status("Extraction pending", "waiting", "View progress", false));
        assertThat(projector.project("FAILED", null).action()).isEqualTo("Retry extraction");
        assertThat(projector.project("READY", "READY").label()).isEqualTo("Ready for review");
        assertThat(projector.project("READY", "ACCEPTED").label()).isEqualTo("Accepted");
        assertThat(projector.project("READY", "REJECTED").label()).isEqualTo("Follow-up required");
        assertThat(projector.project("READY", null).label()).isEqualTo("Findings status needs attention");
        assertThat(projector.project("UNEXPECTED", "READY").label()).isEqualTo("Extraction status needs attention");
        assertThat(projector.project("READY", "UNEXPECTED").label()).isEqualTo("Findings status needs attention");
    }
}
