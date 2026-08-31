package com.findworks.platform.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class InvestigatorUserDetails implements UserDetails {

    private final AuthenticatedInvestigator investigator;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;

    public InvestigatorUserDetails(AuthenticatedInvestigator investigator, String password) {
        this.investigator = investigator;
        this.password = password;
        this.authorities = investigator.authorities().stream().map(SimpleGrantedAuthority::new).toList();
    }

    public AuthenticatedInvestigator investigator() {
        return investigator;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return investigator.email();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
