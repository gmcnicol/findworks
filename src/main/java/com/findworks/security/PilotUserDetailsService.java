package com.findworks.security;

import com.findworks.PilotProperties;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PilotUserDetailsService implements UserDetailsService {

    private final PilotTenant tenant;
    private final String email;
    private final String password;

    PilotUserDetailsService(PilotTenant tenant, PilotProperties properties, PasswordEncoder encoder) {
        this.tenant = tenant;
        this.email = properties.investigatorEmail();
        this.password = encoder.encode(properties.investigatorPassword());
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (!email.equalsIgnoreCase(username)) {
            throw new UsernameNotFoundException("Unknown Investigator.");
        }
        try {
            tenant.investigator(email);
        } catch (AccessDeniedException denied) {
            throw new UsernameNotFoundException("Unknown Investigator.", denied);
        }
        return User.withUsername(email).password(password).roles("INVESTIGATOR").build();
    }
}
