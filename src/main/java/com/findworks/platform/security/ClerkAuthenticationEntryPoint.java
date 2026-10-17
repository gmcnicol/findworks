package com.findworks.platform.security;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class ClerkAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI();
        boolean browserPage = path.startsWith("/app") || path.startsWith("/oauth/") || path.equals("/work")
                || path.equals("/interviews") || path.startsWith("/invite/")
                || (path.startsWith("/interview") && !path.endsWith("/events") && !path.endsWith("/state"));
        if ("GET".equals(request.getMethod()) && browserPage) {
            String destination = request.getRequestURI();
            if (request.getQueryString() != null) destination += "?" + request.getQueryString();
            response.sendRedirect("/signin?returnTo=" + URLEncoder.encode(destination, StandardCharsets.UTF_8));
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }
}
