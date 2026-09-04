package com.findworks.platform.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.findworks.platform.config.FindWorksProperties;

@Component
public class InvestigatorAuthenticationProvider implements AuthenticationProvider {

    private final InvestigatorAccountRepository repository;
    private final FindWorksProperties properties;
    private final PasswordEncoder passwordEncoder;

    public InvestigatorAuthenticationProvider(
            InvestigatorAccountRepository repository,
            FindWorksProperties properties,
            PasswordEncoder passwordEncoder
    ) {
        this.repository = repository;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        InvestigatorAccountRecord account = repository.findPilotAccountByEmail(email, properties.pilotOrganizationSlug())
                .orElseThrow(() -> new BadCredentialsException("Access denied"));

        if (!passwordEncoder.matches(password, account.passwordHash()) || !account.canSignIn()) {
            throw new BadCredentialsException("Access denied");
        }

        InvestigatorUserDetails principal = new InvestigatorUserDetails(account.investigator(), account.passwordHash());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities()
        );
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
