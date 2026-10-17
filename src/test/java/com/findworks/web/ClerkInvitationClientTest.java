package com.findworks.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.findworks.platform.config.ClerkProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class ClerkInvitationClientTest {
    private HttpServer server;
    private ClerkInvitationClient client;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Function<Request, String> response = request -> request.method().equals("GET") ? "[]" : "{\"id\":\"inv_created\"}";
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/invitations", exchange -> {
            var request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(request);
            var body = response.apply(request).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var properties = new ClerkProperties(true, "pk_test_unit", "https://unit.clerk.accounts.dev",
                "https://unit.clerk.accounts.dev", "https://unit.clerk.accounts.dev/.well-known/jwks.json",
                "sk_test_unit", List.of("https://findworks.example"));
        client = new ClerkInvitationClient(RestClient.builder(), properties, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void sends_native_invitation_for_new_or_existing_account_with_scoped_redirect() throws Exception {
        var delivery = UUID.randomUUID();
        assertThat(client.deliver(delivery, "clare@example.com", "https://findworks.example/invite/private-token", 7)).isEqualTo("inv_created");
        assertThat(requests).hasSize(2);
        var body = new ObjectMapper().readTree(requests.get(1).body());
        assertThat(requests.get(1).authorization()).isEqualTo("Bearer sk_test_unit");
        assertThat(body.path("email_address").asText()).isEqualTo("clare@example.com");
        assertThat(body.path("ignore_existing").asBoolean()).isTrue();
        assertThat(body.path("notify").asBoolean()).isTrue();
        assertThat(body.path("expires_in_days").asInt()).isEqualTo(7);
        assertThat(body.path("redirect_url").asText()).isEqualTo("https://findworks.example/signin?returnTo=%2Finvite%2Fprivate-token");
        assertThat(body.path("public_metadata").path("findworks_delivery_id").asText()).isEqualTo(delivery.toString());
        assertThat(body.toString()).doesNotContain("mission_version", "organization_id", "membership");
    }

    @Test
    void recovers_pending_or_already_accepted_delivery_without_sending_another_email() {
        var delivery = UUID.randomUUID();
        for (var state : List.of("pending", "accepted")) {
            response = request -> "[{\"id\":\"inv_recovered\",\"email_address\":\"clare@example.com\",\"status\":\"" + state
                    + "\",\"public_metadata\":{\"findworks_delivery_id\":\"" + delivery + "\"}}]";
            assertThat(client.deliver(delivery, "clare@example.com", "https://findworks.example/invite/private-token", 7)).isEqualTo("inv_recovered");
        }
        assertThat(requests).hasSize(2).allMatch(request -> request.method().equals("GET"));
    }

    @Test
    void revokes_pending_provider_invitation_but_not_an_already_consumed_ticket() {
        response = request -> request.method().equals("GET") ? "[{\"id\":\"inv_pending\"}]" : "{\"id\":\"inv_pending\",\"status\":\"revoked\"}";
        client.revoke("inv_pending");
        assertThat(requests.get(1).uri()).isEqualTo("/v1/invitations/inv_pending/revoke");
        response = request -> "[]";
        client.revoke("inv_accepted");
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).method()).isEqualTo("GET");
    }

    @Test
    void an_inactive_local_invitation_recovers_and_revokes_unknown_provider_result_without_creating_another() {
        var delivery = UUID.randomUUID();
        response = request -> request.method().equals("POST") ? "{}" : request.uri().contains("status=pending")
                ? "[{\"id\":\"inv_recovered\"}]" : "[{\"id\":\"inv_recovered\",\"email_address\":\"clare@example.com\",\"status\":\"pending\",\"public_metadata\":{\"findworks_delivery_id\":\"" + delivery + "\"}}]";
        client.recoverAndRevoke(delivery, "clare@example.com");
        assertThat(requests).hasSize(3);
        assertThat(requests.get(2).uri()).isEqualTo("/v1/invitations/inv_recovered/revoke");
    }

    @Test
    void provider_errors_do_not_leak_email_links_credentials_or_response_bodies() {
        status = 503;
        response = request -> "{\"private\":\"secret-provider-body-and-email-link\"}";
        assertThatThrownBy(() -> client.deliver(UUID.randomUUID(), "clare@example.com", "https://findworks.example/invite/private-token", 7))
                .isInstanceOf(IllegalStateException.class).hasMessage("Clerk invitation service unavailable").hasNoCause();
    }

    private record Request(String method, String uri, String body, String authorization) {}
}
