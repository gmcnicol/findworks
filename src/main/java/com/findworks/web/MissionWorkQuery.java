package com.findworks.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.findworks.platform.security.AuthenticatedInvestigator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class MissionWorkQuery {
    private final JdbcClient db;
    private final MissionResumeProjector projector;

    public MissionWorkQuery(JdbcClient db, MissionResumeProjector projector) {
        this.db = db;
        this.projector = projector;
    }

    public List<MissionWorkItem> listOwned(AuthenticatedInvestigator investigator) {
        return query(investigator, null);
    }

    public List<MissionWorkItem> listForDiscovery(AuthenticatedInvestigator investigator, UUID discoveryId) {
        return query(investigator, discoveryId);
    }

    private List<MissionWorkItem> query(AuthenticatedInvestigator investigator, UUID discoveryId) {
        var query = db.sql("""
                select m.id mission_id,d.id discovery_id,d.title discovery,m.label,m.current_version,mv.state mission_state,
                       s.id session_id,s.state session_state,s.extraction_state,pv.state package_state,
                       exists(select 1 from invitations i where i.session_id=s.id and i.state='DELIVERED'
                         and i.redeemed_at is null and i.revoked_at is null and i.expires_at>now()) invitation_active,
                       exists(select 1 from invitations i where i.session_id=s.id and i.state='PENDING'
                         and i.revoked_at is null and i.expires_at>now()) invitation_pending,
                       exists(select 1 from access_grants g where g.session_id=s.id and g.revoked_at is null
                         and g.expires_at>now()) or exists(select 1 from interview_account_access a
                         where a.session_id=s.id and a.revoked_at is null) browser_access_active,
                       coalesce((select min(c.number) from generate_series(1,5) as c(number)
                         where not exists(select 1 from mission_review r where r.mission_id=m.id
                           and r.version=m.current_version and r.checkpoint=c.number)),6) next_checkpoint,
                       m.last_activity_at
                from missions m
                join discoveries d on d.id=m.discovery_id and d.organization_id=m.organization_id
                join mission_versions mv on mv.mission_id=m.id and mv.version=m.current_version
                left join lateral (
                    select x.id,x.state,x.extraction_state from interview_sessions x
                    where x.mission_id=m.id and x.mission_version=m.current_version and x.deleted_at is null
                    order by x.created_at desc,x.id desc limit 1
                ) s on true
                left join findings_packages fp on fp.session_id=s.id
                left join package_versions pv on pv.package_id=fp.id and pv.version=fp.current_version
                where d.organization_id=:organization and d.owner_membership_id=:member and d.deleted_at is null
                """ + (discoveryId == null ? "" : " and d.id=:discovery ")
                + " order by m.last_activity_at desc,m.id")
                .param("organization", investigator.organizationId()).param("member", investigator.membershipId());
        if (discoveryId != null) query.param("discovery", discoveryId);
        return query.query((rs, row) -> {
            UUID missionId = rs.getObject("mission_id", UUID.class);
            var action = projector.project(new MissionResumeProjector.Snapshot(missionId, rs.getString("mission_state"),
                    rs.getInt("next_checkpoint"), rs.getObject("session_id", UUID.class), rs.getString("session_state"),
                    rs.getString("extraction_state"), rs.getString("package_state"), rs.getBoolean("invitation_active"),
                    rs.getBoolean("invitation_pending"), rs.getBoolean("browser_access_active")));
            return new MissionWorkItem(missionId, rs.getObject("discovery_id", UUID.class), rs.getString("discovery"),
                    rs.getString("label"), rs.getInt("current_version"), action.status(), action.statusTone(), action.label(),
                    action.href(), action.needsAttention(), action.interviewActive(), rs.getObject("last_activity_at", OffsetDateTime.class));
        }).list();
    }

    public record MissionWorkItem(UUID id, UUID discoveryId, String discovery, String label, int version,
                                  String status, String statusTone, String action, String href,
                                  boolean needsAttention, boolean interviewActive, OffsetDateTime lastActivityAt) {}
}
