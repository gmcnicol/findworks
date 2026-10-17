package com.findworks.platform.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/** Clerk authenticates both human roles; MCP remains a separate OAuth credential. */
final class ClerkAuthenticationFilter extends OncePerRequestFilter {
    private final ClerkBearerTokenResolver tokens = new ClerkBearerTokenResolver();
    private final JwtDecoder decoder;
    private final ClerkJwtAuthenticationConverter converter;
    private final ClerkAuthenticationEntryPoint entryPoint;

    ClerkAuthenticationFilter(JwtDecoder decoder, ClerkAccountService accounts,
                              ClerkAuthenticationEntryPoint entryPoint) {
        this.decoder = decoder;
        this.converter = new ClerkJwtAuthenticationConverter(accounts);
        this.entryPoint = entryPoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI();
        return !(path.equals("/app") || path.startsWith("/app/") || path.startsWith("/api/")
                || path.equals("/auth/check") || path.equals("/oauth/authorize")
                || path.equals("/oauth/grants") || path.equals("/oauth/revoke-grant")
                || path.equals("/work") || path.equals("/interviews") || path.equals("/interview")
                || path.startsWith("/interview/") || path.startsWith("/invite/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        final String token;
        try {
            token = tokens.resolve(request);
        } catch (RuntimeException malformed) {
            entryPoint.commence(request, response, new BadCredentialsException("Invalid credentials"));
            return;
        }
        if (token != null) {
            try {
                var authentication = converter.convert(decoder.decode(token));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            } catch (ClerkIdentityRejectedException denied) {
                response.setHeader("Cache-Control", "no-store");
                if ("GET".equals(request.getMethod()) && !request.getRequestURI().equals("/auth/check")
                        && !request.getRequestURI().startsWith("/api/")) {
                    response.sendRedirect("/denied");
                } else response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            } catch (ClerkIdentityUnavailableException unavailable) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setHeader("Retry-After", "5");
                return;
            } catch (JwtException | BadCredentialsException invalid) {
                entryPoint.commence(request, response, new BadCredentialsException("Invalid credentials"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
