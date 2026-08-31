package com.findworks.discovery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DiscoveryRepository {

    private final JdbcClient jdbcClient;

    public DiscoveryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<DiscoverySummary> findActiveOwnedBy(UUID organizationId, UUID membershipId) {
        return jdbcClient.sql("""
                select id, title, objective, created_at
                from discoveries
                where organization_id = :organizationId
                  and owner_membership_id = :membershipId
                  and archived_at is null
                order by created_at desc
                """)
                .param("organizationId", organizationId)
                .param("membershipId", membershipId)
                .query(this::mapSummary)
                .list();
    }

    private DiscoverySummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new DiscoverySummary(
                UUID.fromString(rs.getString("id")),
                rs.getString("title"),
                rs.getString("objective"),
                rs.getObject("created_at", java.time.OffsetDateTime.class)
        );
    }
}
