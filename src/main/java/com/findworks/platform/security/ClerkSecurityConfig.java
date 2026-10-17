package com.findworks.platform.security;

import com.findworks.platform.config.ClerkProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

@Configuration
@ConditionalOnProperty(name = "findworks.clerk.enabled", havingValue = "true")
public class ClerkSecurityConfig {

    @Bean
    JwtDecoder clerkJwtDecoder(ClerkProperties properties) {
        properties.validate();
        var decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuer()),
                new ClerkSessionValidator(),
                new ClerkAuthorizedPartyValidator(properties.authorizedParties())
        ));
        return decoder;
    }

    @Bean
    SecurityFilterChain clerkSecurityFilterChain(HttpSecurity http, JwtDecoder decoder,
                                                  ClerkAccountService accounts) throws Exception {
        var entryPoint = new ClerkAuthenticationEntryPoint();
        http
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers("/", "/error", "/signin", "/signup", "/denied", "/health", "/assets/**", "/invite/**", "/test/**", "/internal/**", "/mcp", "/.well-known/**", "/oauth/register", "/oauth/token", "/oauth/revoke", "/operator/**").permitAll()
                        .requestMatchers("/api/**", "/app/**", "/work", "/interviews", "/interview/**", "/auth/check", "/oauth/authorize", "/oauth/grants", "/oauth/revoke-grant").authenticated()
                        .anyRequest().denyAll())
                // Browser JWTs are revalidated on every protected request. The HTTP session
                // stores CSRF state, never an authentication that could outlive Clerk's token.
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(cache -> cache.disable())
                .addFilterBefore(new ClerkAuthenticationFilter(decoder, accounts, entryPoint),
                        UsernamePasswordAuthenticationFilter.class)
                .logout((logout) -> logout.logoutUrl("/signout").deleteCookies("__session")
                        .logoutSuccessUrl("/?signed_out=1"))
                .exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint(entryPoint)
                        .accessDeniedPage("/denied"))
                // Do not use Resource Server's cookie-aware bearer matcher here: it would
                // exempt authenticated browser form posts from CSRF protection.
                .csrf((csrf) -> csrf.ignoringRequestMatchers("/mcp", "/oauth/register", "/oauth/token", "/oauth/revoke", "/test/**", "/internal/**", "/operator/**"))
                .headers((headers) -> headers.frameOptions(Customizer.withDefaults()));
        return http.build();
    }
}
