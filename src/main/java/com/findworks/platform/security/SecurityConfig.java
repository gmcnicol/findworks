package com.findworks.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SimpleUrlAuthenticationFailureHandler failureHandler = new SimpleUrlAuthenticationFailureHandler("/denied");
        failureHandler.setUseForward(false);

        http
                .authorizeHttpRequests((authorize) -> authorize
                        .requestMatchers("/", "/error", "/signin", "/denied", "/health", "/assets/**", "/invite/**", "/interview/**", "/test/**", "/internal/**", "/mcp", "/.well-known/**", "/oauth/register", "/oauth/token", "/oauth/revoke", "/operator/**").permitAll()
                        .requestMatchers("/api/**", "/app/**", "/oauth/authorize", "/oauth/grants", "/oauth/revoke-grant").authenticated()
                        .anyRequest().denyAll())
                .formLogin((form) -> form
                        .loginPage("/signin")
                        .loginProcessingUrl("/signin")
                        .defaultSuccessUrl("/app")
                        .failureHandler(failureHandler))
                .logout((logout) -> logout
                        .logoutUrl("/signout")
                        .logoutSuccessUrl("/?signed_out=1"))
                .exceptionHandling((exceptions) -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                request -> request.getRequestURI().startsWith("/api/"))
                        .accessDeniedPage("/denied"))
                .csrf((csrf) -> csrf.ignoringRequestMatchers("/api/**", "/mcp", "/oauth/register", "/oauth/token", "/oauth/revoke", "/test/**", "/internal/**", "/operator/**"))
                .headers((headers) -> headers.frameOptions(Customizer.withDefaults()));

        return http.build();
    }
}
