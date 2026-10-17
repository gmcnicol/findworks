package com.findworks.platform.security;

import java.net.http.HttpClient;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import com.findworks.platform.config.ClerkProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@ConditionalOnProperty(name = "findworks.clerk.enabled", havingValue = "true")
public class ClerkUserClient {
    private final RestClient client;

    public ClerkUserClient(RestClient.Builder builder, ClerkProperties properties) {
        properties.validate();
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        transport.setReadTimeout(Duration.ofSeconds(5));
        client = builder.baseUrl("https://api.clerk.com/v1")
                .requestFactory(transport)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
                .defaultHeader(HttpHeaders.USER_AGENT, "FindWorks/1.0").build();
    }

    /** Initial account registration and each invitation claim use the server-verified primary email. */
    public VerifiedClerkUser requireVerifiedPrimaryEmail(String clerkUserId) {
        final JsonNode user;
        try {
            user = client.get().uri("/users/{id}", clerkUserId).retrieve().body(JsonNode.class);
        } catch (RestClientException failure) {
            // Do not propagate provider responses or request headers into application logs.
            throw new ClerkIdentityUnavailableException();
        }
        return verifiedUser(user, clerkUserId);
    }

    static VerifiedClerkUser verifiedUser(JsonNode user, String expectedId) {
        if (user == null || !expectedId.equals(user.path("id").asText())
                || user.path("banned").asBoolean() || user.path("locked").asBoolean()) {
            throw new ClerkIdentityRejectedException();
        }
        String primaryEmailId = user.path("primary_email_address_id").asText("");
        for (JsonNode address : user.path("email_addresses")) {
            String email = address.path("email_address").asText("").trim();
            if (!primaryEmailId.isBlank() && primaryEmailId.equals(address.path("id").asText())
                    && "verified".equals(address.path("verification").path("status").asText()) && !email.isBlank()) {
                return new VerifiedClerkUser(email);
            }
        }
        throw new ClerkIdentityRejectedException();
    }

    public record VerifiedClerkUser(String email) {}
}
