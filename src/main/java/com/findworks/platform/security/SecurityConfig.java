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
                        .requestMatchers("/", "/signin", "/denied", "/health", "/assets/**").permitAll()
                        .requestMatchers("/api/**", "/app/**").authenticated()
                        .anyRequest().denyAll())
                .formLogin((form) -> form
                        .loginPage("/signin")
                        .loginProcessingUrl("/signin")
                        .defaultSuccessUrl("/app", true)
                        .failureHandler(failureHandler))
                .logout((logout) -> logout
                        .logoutUrl("/signout")
                        .logoutSuccessUrl("/?signed_out=1"))
                .exceptionHandling((exceptions) -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                request -> request.getRequestURI().startsWith("/api/"))
                        .accessDeniedPage("/denied"))
                .csrf((csrf) -> csrf.ignoringRequestMatchers("/api/**"))
                .headers((headers) -> headers.frameOptions(Customizer.withDefaults()));

        return http.build();
    }
}
