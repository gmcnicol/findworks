package com.findworks.web;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import com.findworks.platform.config.ClerkProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Native account invitations deliver the email; FindWorks alone grants interview access. */
@Component
@ConditionalOnProperty(name = "findworks.clerk.enabled", havingValue = "true")
class ClerkInvitationClient {
    private final RestClient client;

    ClerkInvitationClient(RestClient.Builder builder, ClerkProperties properties,
                          @Value("${findworks.clerk.invitation-api-base-url:https://api.clerk.com/v1}") String baseUrl) {
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        transport.setReadTimeout(Duration.ofSeconds(10));
        client = builder.baseUrl(baseUrl).requestFactory(transport)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
                .defaultHeader(HttpHeaders.USER_AGENT, "FindWorks/1.0").build();
    }

    String deliver(UUID deliveryId, String recipient, String interviewLink, int expiresInDays) {
        try {
            // Recover a successful but unacknowledged request after process death/timeout.
            // Never assume Clerk honours a generic Idempotency-Key header.
            var existing = find(deliveryId, recipient);
            if (existing.isPresent()) return existing.get();
            var link = URI.create(interviewLink);
            var redirect = link.getScheme() + "://" + link.getRawAuthority() + "/signin?returnTo="
                    + URLEncoder.encode(link.getRawPath(), StandardCharsets.UTF_8);
            var invitation = client.post().uri("/invitations").body(Map.of(
                    "email_address", recipient, "ignore_existing", true, "notify", true,
                    "expires_in_days", expiresInDays, "redirect_url", redirect,
                    "public_metadata", Map.of("findworks_delivery_id", deliveryId.toString())))
                    .retrieve().body(JsonNode.class);
            return id(invitation);
        } catch (Exception failure) {
            // Never attach provider bodies, URLs containing tickets, or request headers.
            throw unavailable();
        }
    }

    void revoke(String invitationId) {
        try {
            if (!pending(invitationId)) return; // Accepted tickets are already consumed.
            try {
                client.post().uri("/invitations/{id}/revoke", invitationId).retrieve().toBodilessEntity();
            } catch (Exception race) {
                if (pending(invitationId)) throw race;
            }
        } catch (Exception failure) { throw unavailable(); }
    }

    void recoverAndRevoke(UUID deliveryId, String recipient) {
        try { find(deliveryId, recipient).ifPresent(this::revoke); }
        catch (Exception failure) { throw unavailable(); }
    }

    private java.util.Optional<String> find(UUID deliveryId, String recipient) {
        for (int offset = 0; ; offset += 100) {
            var invitations = list(recipient, null, offset, 100);
            for (var invitation : invitations) {
                if (recipient.equalsIgnoreCase(invitation.path("email_address").asText())
                        && deliveryId.toString().equals(invitation.path("public_metadata").path("findworks_delivery_id").asText())
                        && (invitation.path("status").asText().equals("pending")
                        || invitation.path("status").asText().equals("accepted"))) return java.util.Optional.of(id(invitation));
            }
            if (invitations.size() < 100) return java.util.Optional.empty();
        }
    }

    private boolean pending(String invitationId) {
        for (var invitation : list(invitationId, "pending", 0, 100)) {
            if (invitationId.equals(invitation.path("id").asText())) return true;
        }
        return false;
    }

    private JsonNode list(String query, String status, int offset, int limit) {
        var result = client.get().uri(builder -> {
            builder.path("/invitations").queryParam("query", query).queryParam("offset", offset).queryParam("limit", limit);
            if (status != null) builder.queryParam("status", status);
            return builder.build();
        }).retrieve().body(JsonNode.class);
        if (result == null || !result.isArray()) throw unavailable();
        return result;
    }

    private String id(JsonNode invitation) {
        var id = invitation == null ? "" : invitation.path("id").asText();
        if (!id.matches("inv_[A-Za-z0-9_-]+")) throw unavailable();
        return id;
    }

    private IllegalStateException unavailable() { return new IllegalStateException("Clerk invitation service unavailable"); }
}
