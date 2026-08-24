package com.findworks.operations;

import com.findworks.operations.OperationalTelemetry.HttpMethod;
import com.findworks.operations.OperationalTelemetry.HttpRoute;
import com.findworks.operations.OperationalTelemetry.Outcome;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'web'")
public final class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    private final CorrelationContext correlations;
    private final OperationalTelemetry telemetry;
    private final boolean trustProxy;

    CorrelationFilter(CorrelationContext correlations, OperationalTelemetry telemetry,
            @Value("${findworks.operations.trust-proxy-correlation:false}") boolean trustProxy) {
        this.correlations = correlations;
        this.telemetry = telemetry;
        this.trustProxy = trustProxy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var started = System.nanoTime();
        var correlationId = trusted(request.getHeader(HEADER));
        response.setHeader(HEADER, correlationId.toString());
        try (var ignored = correlations.open(correlationId)) {
            chain.doFilter(request, response);
        } finally {
            telemetry.request(method(request.getMethod()), route(request.getRequestURI()),
                    outcome(response.getStatus()), Duration.ofNanos(System.nanoTime() - started), correlationId);
        }
    }

    private UUID trusted(String value) {
        if (trustProxy && value != null && value.length() == 36) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Invalid untrusted input gets replaced.
            }
        }
        return UUID.randomUUID();
    }

    private static HttpMethod method(String method) {
        return switch (method) { case "GET" -> HttpMethod.GET; case "POST" -> HttpMethod.POST; default -> HttpMethod.OTHER; };
    }

    private static HttpRoute route(String uri) {
        if (uri.startsWith("/actuator")) return HttpRoute.HEALTH;
        if (uri.startsWith("/operations")) return HttpRoute.OPERATIONS;
        if (uri.startsWith("/interview") || uri.startsWith("/i/")) return HttpRoute.INTERVIEW;
        if (uri.contains("/findings")) return HttpRoute.FINDINGS;
        if (uri.contains("/missions")) return HttpRoute.MISSION;
        if (uri.startsWith("/discoveries")) return HttpRoute.DISCOVERY;
        if ("/".equals(uri) || uri.startsWith("/login")) return HttpRoute.HOME;
        return HttpRoute.OTHER;
    }

    private static Outcome outcome(int status) {
        if (status == 401 || status == 403 || status == 404) return Outcome.DENIED;
        return status < 400 ? Outcome.SUCCESS : Outcome.FAILED;
    }
}
