package com.findworks.platform.security;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

class ClerkUserClientTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void only_the_verified_primary_email_is_eligible_for_linking() {
        var user = json.readTree("""
                {"id":"user_1","primary_email_address_id":"primary","email_addresses":[
                  {"id":"secondary","email_address":"gareth@example.com","verification":{"status":"verified"}},
                  {"id":"primary","email_address":"owner@example.com","verification":{"status":"verified"}}
                ]}
                """);
        assertThat(ClerkUserClient.verifiedUser(user, "user_1").email()).isEqualTo("owner@example.com");
    }

    @Test
    void unverified_banned_locked_or_mismatched_users_are_rejected() {
        for (String fields : new String[] {"\"banned\":true,", "\"locked\":true,", ""}) {
            var user = json.readTree("""
                    {"id":"user_1",%s"primary_email_address_id":"primary","email_addresses":[
                      {"id":"primary","email_address":"owner@example.com","verification":{"status":"%s"}}
                    ]}
                    """.formatted(fields, fields.isEmpty() ? "unverified" : "verified"));
            assertThatThrownBy(() -> ClerkUserClient.verifiedUser(user, "user_1")).isInstanceOf(ClerkIdentityRejectedException.class);
        }
        assertThatThrownBy(() -> ClerkUserClient.verifiedUser(json.readTree("{\"id\":\"other_user\"}"), "user_1"))
                .isInstanceOf(ClerkIdentityRejectedException.class);
    }
}
