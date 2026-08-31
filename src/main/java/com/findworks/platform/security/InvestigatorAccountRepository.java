package com.findworks.platform.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class InvestigatorAccountRepository {

    private final JdbcClient jdbcClient;

    public InvestigatorAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<InvestigatorAccountRecord> findPilotAccountByEmail(String email, String pilotOrganizationSlug) {
        return jdbcClient.sql("""
                select
                    u.id as user_id,
                    u.email,
                    u.display_name,
                    u.password_hash,
                    u.active as user_active,
                    u.email_verified,
                    m.id as membership_id,
                    m.active as membership_active,
                    o.id as organization_id,
                    o.slug as organization_slug,
                    o.name as organization_name,
                    o.active as organization_active
                from users u
                join memberships m on m.user_id = u.id
                join organizations o on o.id = m.organization_id
                where lower(u.email) = lower(:email)
                  and o.slug = :organizationSlug
                order by u.created_at asc
                limit 1
                """)
                .param("email", email)
                .param("organizationSlug", pilotOrganizationSlug)
                .query(this::mapRecord)
                .optional();
    }

    public Optional<AuthenticatedInvestigator> findActiveByMembershipId(UUID membershipId, String pilotOrganizationSlug) {
        return jdbcClient.sql("""
                select
                    u.id as user_id,
                    u.email,
                    u.display_name,
                    m.id as membership_id,
                    o.id as organization_id,
                    o.slug as organization_slug,
                    o.name as organization_name
                from memberships m
                join users u on u.id = m.user_id
                join organizations o on o.id = m.organization_id
                where m.id = :membershipId
                  and o.slug = :organizationSlug
                  and u.active = true
                  and u.email_verified = true
                  and m.active = true
                  and o.active = true
                """)
                .param("membershipId", membershipId)
                .param("organizationSlug", pilotOrganizationSlug)
                .query((rs, rowNum) -> mapInvestigator(rs))
                .optional();
    }

    private InvestigatorAccountRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new InvestigatorAccountRecord(
                mapInvestigator(rs),
                rs.getString("password_hash"),
                rs.getBoolean("user_active"),
                rs.getBoolean("email_verified"),
                rs.getBoolean("membership_active"),
                rs.getBoolean("organization_active")
        );
    }

    private AuthenticatedInvestigator mapInvestigator(ResultSet rs) throws SQLException {
        return new AuthenticatedInvestigator(
                UUID.fromString(rs.getString("user_id")),
                UUID.fromString(rs.getString("membership_id")),
                UUID.fromString(rs.getString("organization_id")),
                rs.getString("organization_slug"),
                rs.getString("organization_name"),
                rs.getString("display_name"),
                rs.getString("email"),
                List.of("ROLE_INVESTIGATOR")
        );
    }
}
