package com.findworks.web;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps every Session-ending path on the one reviewable completion lifecycle. */
@Component
public class InterviewSessionLifecycle {
    private final JdbcClient db;

    public InterviewSessionLifecycle(JdbcClient db) {
        this.db = db;
    }

    @Transactional
    public boolean complete(UUID sessionId) {
        supersedeRuntimeWork(sessionId);
        var changed = db.sql("""
                update interview_sessions
                set state='COMPLETED',completed_at=coalesce(completed_at,now()),revision=revision+1
                where id=:session and state<>'COMPLETED'
                """).param("session", sessionId).update() == 1;
        // Catch a run inserted by an answer transaction that held the Session lock when completion began.
        supersedeRuntimeWork(sessionId);
        db.sql("update questions set active=false where session_id=:session and active")
                .param("session", sessionId).update();
        return changed;
    }

    private void supersedeRuntimeWork(UUID sessionId) {
        db.sql("""
                update runtime_runs set state='SUPERSEDED',lease_until=null
                where session_id=:session and state in ('PENDING','RUNNING')
                """).param("session", sessionId).update();
    }

    @Transactional
    public boolean terminateAccess(UUID sessionId) {
        var changed = complete(sessionId);
        db.sql("update access_grants set revoked_at=now() where session_id=:session and revoked_at is null")
                .param("session", sessionId).update();
        db.sql("update interview_account_access set revoked_at=now() where session_id=:session and revoked_at is null")
                .param("session", sessionId).update();
        db.sql("""
                update invitations set state='REVOKED',revoked_at=coalesce(revoked_at,now())
                where session_id=:session and state<>'REVOKED'
                """).param("session", sessionId).update();
        return changed;
    }
}
