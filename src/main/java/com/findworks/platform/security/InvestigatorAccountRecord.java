package com.findworks.platform.security;

public record InvestigatorAccountRecord(
        AuthenticatedInvestigator investigator,
        String passwordHash,
        boolean active,
        boolean emailVerified,
        boolean membershipActive,
        boolean organizationActive
) {
    public boolean canSignIn() {
        return active && emailVerified && membershipActive && organizationActive;
    }
}
