package com.findworks.web;

import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.findworks.platform.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnExpression("'${findworks.process-mode:web}' == 'worker' or '${findworks.process-mode:web}' == 'all'")
@ConditionalOnProperty(name = "findworks.test-support", havingValue = "false", matchIfMissing = true)
public class RuntimeContainerWorker {
    private final JdbcClient db;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final String command;
    private final String image;
    private final String network;
    private final String internalUrl;
    private final int wallSeconds;

    RuntimeContainerWorker(JdbcClient db, TransactionTemplate transactions, ObjectMapper mapper,
                           @Value("${findworks.runtime-command}") String command,
                           @Value("${findworks.runtime-image}") String image,
                           @Value("${findworks.runtime-network}") String network,
                           @Value("${findworks.runtime-internal-url}") String internalUrl,
                           @Value("${findworks.runtime-wall-seconds}") int wallSeconds) {
        this.db = db;
        this.transactions = transactions;
        this.mapper = mapper;
        this.command = command;
        this.image = image;
        this.network = network;
        this.internalUrl = internalUrl;
        this.wallSeconds = wallSeconds;
    }

    boolean runNext() {
        var claim = transactions.execute(status -> claim());
        if (claim == null) return false;
        var containerName = "findworks-runtime-" + claim.runId();
        try {
            destroyContainer(containerName);
            var args = new ArrayList<>(List.of(command, "run", "--rm", "-i", "--read-only", "--network", network,
                    "--name", containerName,
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--cap-drop", "ALL",
                    "--security-opt", "no-new-privileges", "--pids-limit", "64", "--memory", "512m",
                    "--cpus", "0.5", "--user", "10001:10001", "--env", "OPENAI_API_KEY",
                    "--env", "FINDWORKS_MODEL_OAUTH_B64",
                    "--env", "HTTPS_PROXY", "--env", "NO_PROXY", "--env", "FINDWORKS_MODEL_PROVIDER",
                    "--env", "FINDWORKS_MODEL", "--env", "FINDWORKS_TURN_URL", "--env", "FINDWORKS_TURN_TOKEN", image));
            var builder = new ProcessBuilder(args);
            builder.environment().put("FINDWORKS_TURN_URL", internalUrl);
            builder.environment().put("FINDWORKS_TURN_TOKEN", claim.rawToken());
            var process = builder.start();
            var heartbeat = Thread.startVirtualThread(() -> heartbeat(claim.runId(), process));
            Thread.startVirtualThread(() -> drain(process.getInputStream()));
            Thread.startVirtualThread(() -> drain(process.getErrorStream()));
            try (var stdin = process.getOutputStream()) {
                mapper.writeValue(stdin, context(claim));
                stdin.write('\n');
            }
            var settled = process.waitFor(wallSeconds, TimeUnit.SECONDS);
            if (!settled) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            heartbeat.interrupt();
            var committed = db.sql("select state='COMPLETED' from runtime_runs where id=:id")
                    .param("id", claim.runId()).query(Boolean.class).single();
            if (!committed) fail(claim.runId(), settled ? "RUNTIME_EXIT" : "RUNTIME_TIMEOUT");
        } catch (Exception exception) {
            fail(claim.runId(), "RUNTIME_START_FAILED");
        } finally {
            destroyContainer(containerName);
        }
        return true;
    }

    private Claim claim() {
        if (!db.sql("select pg_try_advisory_xact_lock(470058)").query(Boolean.class).single()) return null;
        db.sql("""
                update interview_sessions set state='RUNTIME_FAILED'
                where id in(select session_id from runtime_runs where state='RUNNING' and lease_until<now() and restart_count>=1)
                """).update();
        db.sql("update runtime_runs set state='FAILED',lease_until=null,error_code='PROCESS_DIED',stable_event='runtime_failed' where state='RUNNING' and lease_until<now() and restart_count>=1").update();
        if (db.sql("select count(*) from runtime_runs where state='RUNNING' and lease_until>now()").query(Long.class).single() >= 2) return null;
        var run = db.sql("""
                select r.id,r.organization_id,r.session_id,r.expected_revision,s.mission_version,r.state
                from runtime_runs r join interview_sessions s on s.id=r.session_id
                where (r.state='PENDING' or (r.state='RUNNING' and r.lease_until<now() and r.restart_count=0))
                  and r.attempts<3 order by r.created_at limit 1 for update of r skip locked
                """).query((rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getInt(4), rs.getInt(5), rs.getString(6), null)).optional().orElse(null);
        if (run == null) return null;
        db.sql("update runtime_runs set state='RUNNING',attempts=attempts+1,restart_count=restart_count+:restart,lease_until=now()+(:lease||' seconds')::interval where id=:id")
                .param("restart", run.previousState().equals("RUNNING") ? 1 : 0).param("lease", wallSeconds + 30).param("id", run.runId()).update();
        var checkpoint = run.sessionId() + ":" + run.missionVersion() + ":" + run.expectedRevision();
        db.sql("delete from runtime_checkpoints where run_id=:run and (session_id<>:session or mission_version<>:mission or expected_revision<>:revision or checksum<>:checksum)")
                .param("run", run.runId()).param("session", run.sessionId()).param("mission", run.missionVersion())
                .param("revision", run.expectedRevision()).param("checksum", Ids.sha(checkpoint)).update();
        db.sql("""
                insert into runtime_checkpoints(run_id,session_id,mission_version,expected_revision,state,location,checksum)
                values(:run,:session,:mission,:revision,'READY',:location,:checksum)
                on conflict(run_id) do update set state='READY',location=excluded.location,checksum=excluded.checksum,created_at=now()
                """).param("run", run.runId()).param("session", run.sessionId()).param("mission", run.missionVersion())
                .param("revision", run.expectedRevision()).param("location", "private://" + run.runId())
                .param("checksum", Ids.sha(checkpoint)).update();
        db.sql("update runtime_turn_credentials set expires_at=now() where run_id=:run and used_at is null")
                .param("run", run.runId()).update();
        var raw = Ids.token();
        db.sql("insert into runtime_turn_credentials values(:hash,:run,now()+interval '5 minutes',null)")
                .param("hash", Ids.sha(raw)).param("run", run.runId()).update();
        return new Claim(run.runId(), run.organizationId(), run.sessionId(), run.expectedRevision(), run.missionVersion(), run.previousState(), raw);
    }

    private Map<String, Object> context(Claim claim) {
        var result = new LinkedHashMap<String, Object>();
        result.put("run_id", claim.runId());
        result.put("session_id", claim.sessionId());
        result.put("mission_version", claim.missionVersion());
        result.put("expected_revision", claim.expectedRevision());
        result.put("mission", db.sql("""
                select mv.objective,mv.desired_outcome,mv.expected_minutes,mv.data_use
                from interview_sessions s join mission_versions mv on mv.mission_id=s.mission_id and mv.version=s.mission_version
                where s.id=:session
                """).param("session", claim.sessionId()).query((rs, row) -> Map.of(
                "objective", rs.getString(1), "desired_outcome", rs.getString(2),
                "expected_minutes", rs.getInt(3), "data_use", rs.getString(4))).single());
        result.put("shared_mission_entries", db.sql("""
                select e.kind,e.position,e.value,e.detail from interview_sessions s join mission_entries e on e.mission_id=s.mission_id and e.version=s.mission_version
                where s.id=:session order by e.kind,e.position
                """).param("session", claim.sessionId()).query((rs, row) -> { var entry = new LinkedHashMap<String, Object>(); entry.put("kind", rs.getString(1)); entry.put("position", rs.getInt(2)); entry.put("value", rs.getString(3)); if (rs.getString(4) != null) entry.put("detail", rs.getString(4)); return entry; }).list());
        result.put("investigation_results", db.sql("""
                select r.id,i.gap,i.why,i.priority,i.required,i.context,i.sufficient,r.coverage
                from investigation_results r join investigation_items i on i.id=r.item_id where r.session_id=:session order by i.position
                """).param("session", claim.sessionId()).query((rs, row) -> Map.of(
                "result_id", rs.getObject(1), "knowledge_gap", rs.getString(2), "why_it_matters", rs.getString(3),
                "priority", rs.getString(4), "required", rs.getBoolean(5), "relevant_context", rs.getString(6),
                "sufficient_evidence", rs.getString(7), "coverage", rs.getString(8))).list());
        result.put("questions_and_evidence", db.sql("""
                select q.id,q.sequence,q.text,e.id,e.source_type,e.exact_text,e.option_id,e.option_label,e.explanation
                from questions q left join evidence e on e.question_id=q.id where q.session_id=:session order by q.sequence,e.created_at,e.id
                """).param("session", claim.sessionId()).query((rs, row) -> { var turn = new LinkedHashMap<String, Object>(); turn.put("question_id", rs.getObject(1)); turn.put("sequence", rs.getInt(2)); turn.put("question", rs.getString(3)); if (rs.getObject(4) != null) { turn.put("evidence_id", rs.getObject(4)); turn.put("source_type", rs.getString(5)); turn.put("exact_text", rs.getString(6)); if (rs.getString(7) != null) turn.put("option_id", rs.getString(7)); if (rs.getString(8) != null) turn.put("option_label", rs.getString(8)); if (rs.getString(9) != null) turn.put("explanation", rs.getString(9)); } return turn; }).list());
        return result;
    }

    private void fail(UUID runId, String code) {
        transactions.executeWithoutResult(status -> {
            var attempts = db.sql("select attempts from runtime_runs where id=:id and state='RUNNING' for update")
                    .param("id", runId).query(Integer.class).optional();
            if (attempts.isEmpty()) return;
            if (attempts.get() < 3) {
                db.sql("update runtime_runs set state='PENDING',lease_until=null,error_code=:code where id=:id").param("code", code).param("id", runId).update();
            } else {
                db.sql("update runtime_runs set state='FAILED',lease_until=null,error_code=:code,stable_event='runtime_failed' where id=:id").param("code", code).param("id", runId).update();
                db.sql("update interview_sessions set state='RUNTIME_FAILED' where id=(select session_id from runtime_runs where id=:id)").param("id", runId).update();
            }
        });
    }

    private static void drain(java.io.InputStream input) {
        try (input) { input.transferTo(OutputStream.nullOutputStream()); } catch (Exception ignored) {}
    }

    private void destroyContainer(String name) {
        try {
            new ProcessBuilder(command, "rm", "-f", name)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    private void heartbeat(UUID runId, Process process) {
        try {
            while (process.isAlive()) {
                Thread.sleep(Duration.ofSeconds(10));
                db.sql("update runtime_runs set lease_until=now()+(:lease||' seconds')::interval where id=:id and state='RUNNING'")
                        .param("lease", wallSeconds + 30).param("id", runId).update();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private record Claim(UUID runId, UUID organizationId, UUID sessionId, int expectedRevision,
                         int missionVersion, String previousState, String rawToken) {}
}
