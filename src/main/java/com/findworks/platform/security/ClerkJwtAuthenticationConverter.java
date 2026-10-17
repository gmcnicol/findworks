package com.findworks.platform.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public final class ClerkJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final ClerkAccountService accounts;

    public ClerkJwtAuthenticationConverter(ClerkAccountService accounts) { this.accounts = accounts; }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) throw new BadCredentialsException("Clerk token has no subject");
        accounts.register(subject);
        return UsernamePasswordAuthenticationToken.authenticated(new ClerkPrincipal(subject), null,
                List.of(new SimpleGrantedAuthority("ROLE_CLERK_USER")));
    }
}
