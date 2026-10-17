package com.findworks.platform.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClerkBearerTokenResolverTest {

    private final ClerkBearerTokenResolver resolver = new ClerkBearerTokenResolver();

    @Test
    void resolves_same_origin_clerk_session_cookie() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("__session", "session-jwt"));

        assertThat(resolver.resolve(request)).isEqualTo("session-jwt");
    }

    @Test
    void authorization_header_takes_precedence() {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer header-jwt");
        request.setCookies(new Cookie("__session", "session-jwt"));

        assertThat(resolver.resolve(request)).isEqualTo("header-jwt");
    }
}
