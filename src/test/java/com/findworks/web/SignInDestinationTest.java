package com.findworks.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignInDestinationTest {
    @Test
    void preserves_local_work_and_oauth_query_parameters() {
        assertThat(SignInDestination.safe("/app/missions/123/review#items-heading")).isEqualTo("/app/missions/123/review#items-heading");
        assertThat(SignInDestination.safe("/oauth/authorize?state=a%26b&client_id=client1"))
                .isEqualTo("/oauth/authorize?state=a%26b&client_id=client1");
        for (String path : new String[] {"/work", "/interviews", "/invite/secret-token",
                "/interview/12345678-1234-1234-1234-123456789abc"}) {
            assertThat(SignInDestination.safe(path)).isEqualTo(path);
        }
    }

    @Test
    void rejects_external_encoded_external_and_path_traversal_destinations() {
        for (String candidate : new String[] {"https://evil.test", "//evil.test", "/\\evil.test", "/app/../../evil",
                "/%2f%2fevil.test", "/app/../signin", "javascript:alert(1)", "/signout", "/interview"}) {
            assertThat(SignInDestination.safe(candidate)).isEqualTo("/work");
        }
    }
}
