package com.findworks.web;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.findworks.platform.Ids;
import com.findworks.platform.security.CurrentInvestigatorService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Controller
public class InvitationController {

    private static final String INTERVIEW_COOKIE = "findworks_interview";

    private final JdbcClient db;
    private final CurrentInvestigatorService currentInvestigator;
    private final TransactionTemplate transactions;
    private final String baseUrl;
    private final boolean testSupport;
    private final boolean secureCookie;
    private final EmailDeliveryService emailDelivery;
    private final InterviewEventStream eventStream;
    private final com.findworks.platform.config.ClerkProperties clerkProperties;
    private final org.springframework.beans.factory.ObjectProvider<ClerkInterviewAccess> accountAccess;
    private final HttpServletRequest currentRequest;

    public InvitationController(JdbcClient db, CurrentInvestigatorService currentInvestigator,
                                TransactionTemplate transactions,
                                @Value("${findworks.base-url}") String baseUrl,
                                @Value("${findworks.test-support:false}") boolean testSupport,
                                @Value("${findworks.cookie-secure:true}") boolean secureCookie,
                                EmailDeliveryService emailDelivery,
                                InterviewEventStream interviewEvents,
                                com.findworks.platform.config.ClerkProperties clerkProperties,
                                org.springframework.beans.factory.ObjectProvider<ClerkInterviewAccess> accountAccess,
                                HttpServletRequest currentRequest) {
        this.db = db;
        this.currentInvestigator = currentInvestigator;
        this.transactions = transactions;
        this.baseUrl = baseUrl;
        this.testSupport = testSupport;
        this.secureCookie = secureCookie;
        this.emailDelivery = emailDelivery;
        this.eventStream = interviewEvents;
        this.clerkProperties = clerkProperties;
        this.accountAccess = accountAccess;
        this.currentRequest = currentRequest;
    }

    @PostMapping("/app/missions/{missionId}/invite")
    String invite(@PathVariable UUID missionId, @RequestParam int version, @RequestParam String name,
                  @RequestParam String email, @RequestParam(defaultValue = "manual") String delivery,
                  Authentication authentication, Model model) {
        var investigator = currentInvestigator.require(authentication);
        var participantName = name.trim();
        var recipient = email.trim().toLowerCase();
        var manual = delivery.equals("manual");
        if ((!manual && !delivery.equals("email")) || participantName.isEmpty() || participantName.length() > 120
                || recipient.length() > 254 || !recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid recipient");
        }
        var created = transactions.execute(status -> {
            // Serialise reissues for this Mission so concurrent sends cannot leave two valid invitations.
            db.sql("select pg_advisory_xact_lock(hashtextextended(:mission,0))")
                    .param("mission", missionId.toString()).query((rs, row) -> true).single();
            var approved = db.sql("""
                    select d.id,m.organization_id
                    from missions m join discoveries d on d.id=m.discovery_id and d.organization_id=m.organization_id
                    where m.id=:mission and m.approved_version=:version
                      and d.owner_membership_id=:member and d.organization_id=:organization and d.state='ACTIVE'
                    """).param("mission", missionId).param("version", version)
                    .param("member", investigator.membershipId()).param("organization", investigator.organizationId())
                    .query((rs, row) -> new ApprovedMission(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                    .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Approve this exact version first"));
            var recent = db.sql("select count(*) from invitations where session_id in(select id from interview_sessions where mission_id=:mission) and expires_at>now()+interval '6 days'")
                    .param("mission", missionId).query(Long.class).single();
            if (recent >= 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Try again later");

            var existing = db.sql("""
                    select s.id,s.participant_id,s.state,s.mission_version,p.name,p.email
                    from interview_sessions s join participants p on p.id=s.participant_id
                    where s.mission_id=:mission and s.deleted_at is null
                    order by s.created_at desc,s.id desc limit 1 for update of s,p
                    """).param("mission", missionId)
                    .query((rs, row) -> new ExistingSession(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6))).optional();
            UUID sessionId;
            if (existing.isPresent()) {
                if (java.util.Set.of("COMPLETED", "ENDED_EARLY", "TERMINATED").contains(existing.get().state())) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "This Interview Session has ended");
                }
                var previous = existing.get();
                sessionId = previous.id();
                if (previous.state().equals("NOT_STARTED")) {
                    db.sql("update participants set name=:name,email=:email where id=:id")
                            .param("name", participantName).param("email", recipient).param("id", previous.participantId()).update();
                    db.sql("update interview_sessions set mission_version=:version where id=:id")
                            .param("version", version).param("id", sessionId).update();
                } else if (previous.missionVersion() != version || !previous.email().equalsIgnoreCase(recipient)
                        || !previous.name().equals(participantName)) {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Reissue must preserve the original interviewee and approved Mission version");
                }
                db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id=:session and state<>'REVOKED'")
                        .param("session", sessionId).update();
                db.sql("update access_grants set revoked_at=now() where session_id=:session and revoked_at is null")
                        .param("session", sessionId).update();
            } else {
                var participantId = Ids.id();
                sessionId = Ids.id();
                db.sql("insert into participants values(:id,:organization,:discovery,:name,:email)")
                        .param("id", participantId).param("organization", approved.organizationId())
                        .param("discovery", approved.discoveryId()).param("name", participantName).param("email", recipient).update();
                db.sql("insert into interview_sessions(id,organization_id,discovery_id,mission_id,mission_version,participant_id) values(:id,:organization,:discovery,:mission,:version,:participant)")
                        .param("id", sessionId).param("organization", approved.organizationId()).param("discovery", approved.discoveryId())
                        .param("mission", missionId).param("version", version).param("participant", participantId).update();
            }
            var raw = Ids.token();
            var invitationId = Ids.id();
            if (manual) {
                db.sql("insert into invitations values(:id,:organization,:session,:email,:hash,'DELIVERED',now()+(:validitySeconds * interval '1 second'),now(),null,null)")
                        .param("id", invitationId).param("organization", approved.organizationId()).param("session", sessionId)
                        .param("email", recipient).param("hash", Ids.sha(raw))
                        .param("validitySeconds", InvitationPolicy.VALIDITY_SECONDS).update();
                audit(approved.organizationId(), "INVESTIGATOR", investigator.membershipId(),
                        "MANUAL_INVITATION_LINK_GENERATED", sessionId, "SUCCEEDED");
            } else {
                db.sql("insert into invitations values(:id,:organization,:session,:email,:hash,'PENDING',now()+(:validitySeconds * interval '1 second'),null,null,null)")
                        .param("id", invitationId).param("organization", approved.organizationId()).param("session", sessionId)
                        .param("email", recipient).param("hash", Ids.sha(raw))
                        .param("validitySeconds", InvitationPolicy.VALIDITY_SECONDS).update();
            }
            var link = baseUrl + "/invite/" + raw;
            if (!manual) emailDelivery.queue(invitationId, approved.organizationId(), recipient, link);
            return new CreatedInvitation(missionId, recipient, link);
        });
        if (!manual) return "redirect:/app/missions/" + missionId + "/review";
        model.addAttribute("investigator", investigator);
        model.addAttribute("invitation", created);
        return "manual-invitation-link";
    }

    @GetMapping("/test/emails/latest")
    @ResponseBody
    ResponseEntity<?> latestEmail(@RequestParam String recipient) {
        if (!testSupport) return ResponseEntity.notFound().build();
        return db.sql("select kind,recipient,link from test_emails where recipient=:recipient order by created_at desc limit 1")
                .param("recipient", recipient)
                .query((rs, row) -> Map.of("kind", rs.getString(1), "recipient", rs.getString(2), "link", rs.getString(3)))
                .optional().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/invite/{token}")
    Object invitation(@PathVariable String token, HttpServletRequest request, Model model) {
        var limited = invitationRateLimit(request);
        if (limited.isPresent()) return limited.get();
        if (clerkProperties.enabled()) {
            if (!(request.getUserPrincipal() instanceof Authentication authentication)) {
                return "redirect:/signin?returnTo=" + java.net.URLEncoder.encode(request.getRequestURI(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var claimed = accountAccess.getObject().previouslyClaimed(token, ClerkInterviewAccess.subject(authentication));
            if (claimed.isPresent()) return "redirect:/interview/" + claimed.get();
        }
        var available = db.sql("""
                select count(*) from invitations
                where token_hash=:hash and state='DELIVERED' and redeemed_at is null
                  and revoked_at is null and expires_at>now()
                """).param("hash", Ids.sha(token)).query(Long.class).single() == 1;
        if (!available) {
            recordDeniedInvitationAttempt(request);
            return unavailableInvitation();
        }
        model.addAttribute("token", token);
        return "invitation-accept";
    }

    @PostMapping("/invite/{token}")
    Object redeem(@PathVariable String token, HttpServletRequest request, HttpServletResponse response) {
        var limited = invitationRateLimit(request);
        if (limited.isPresent()) return limited.get();
        if (clerkProperties.enabled()) {
            if (!(request.getUserPrincipal() instanceof Authentication authentication)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            try {
                UUID sessionId = accountAccess.getObject().claim(token, ClerkInterviewAccess.subject(authentication));
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/interview/" + sessionId)).build();
            } catch (ResponseStatusException failure) {
                recordDeniedInvitationAttempt(request);
                denied("INVITATION_REDEMPTION_DENIED", null, null);
                var view = new org.springframework.web.servlet.ModelAndView("invitation-access-denied");
                view.setStatus(failure.getStatusCode());
                view.addObject("signInDestination", request.getRequestURI());
                return view;
            } catch (com.findworks.platform.security.ClerkIdentityUnavailableException unavailable) {
                response.setHeader("Retry-After", "5");
                var view = new org.springframework.web.servlet.ModelAndView("invitation-access-denied");
                view.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
                view.addObject("signInDestination", request.getRequestURI());
                view.addObject("retryable", true);
                return view;
            }
        }
        var rawGrant = Ids.token();
        var redeemed = transactions.execute(status -> db.sql("""
                select i.id,i.organization_id,i.session_id
                from invitations i
                where i.token_hash=:hash and i.state='DELIVERED' and i.redeemed_at is null
                  and i.revoked_at is null and i.expires_at>now()
                for update
                """).param("hash", Ids.sha(token))
                .query((rs, row) -> new Redeemable(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)))
                .optional().map(invitation -> {
                    db.sql("update invitations set state='REDEEMED',redeemed_at=now() where id=:id")
                            .param("id", invitation.id()).update();
                    db.sql("insert into access_grants(id,organization_id,session_id,token_hash,last_used_at,expires_at) values(:id,:organization,:session,:hash,now(),now()+(:validitySeconds * interval '1 second'))")
                            .param("id", Ids.id()).param("organization", invitation.organizationId())
                            .param("session", invitation.sessionId()).param("hash", Ids.sha(rawGrant))
                            .param("validitySeconds", InvitationPolicy.VALIDITY_SECONDS).update();
                    return invitation;
                }));
        if (redeemed == null || redeemed.isEmpty()) {
            recordDeniedInvitationAttempt(request);
            return unavailableInvitation();
        }
        var cookie = ResponseCookie.from(INTERVIEW_COOKIE, rawGrant).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/interview").maxAge(Duration.ofDays(7)).build();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/interview"))
                .header(HttpHeaders.SET_COOKIE, cookie.toString()).build();
    }

    private Optional<ResponseEntity<String>> invitationRateLimit(HttpServletRequest request) {
        db.sql("delete from invitation_access_attempts where created_at<now()-interval '1 day'").update();
        var recent = db.sql("select count(*) from invitation_access_attempts where remote_hash=:remote and created_at>now()-interval '10 minutes'")
                .param("remote", remoteHash(request)).query(Long.class).single();
        if (recent < 30) return Optional.empty();
        recordDeniedInvitationAttempt(request);
        denied("INVITATION_REDEMPTION_RATE_LIMITED", null, null);
        return Optional.of(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.TEXT_HTML)
                .body("<h1>Too many invitation attempts.</h1><p>Wait before trying again.</p>"));
    }

    private void recordDeniedInvitationAttempt(HttpServletRequest request) {
        db.sql("insert into invitation_access_attempts values(:id,:remote,now())")
                .param("id", Ids.id()).param("remote", remoteHash(request)).update();
    }

    private String remoteHash(HttpServletRequest request) {
        var forwarded = request.getHeader("X-Forwarded-For");
        return Ids.sha((forwarded == null ? request.getRemoteAddr() : forwarded.split(",", 2)[0]).trim());
    }

    private ResponseEntity<String> unavailableInvitation() {
        denied("INVITATION_REDEMPTION_DENIED", null, null);
        return ResponseEntity.status(HttpStatus.GONE).contentType(MediaType.TEXT_HTML)
                .body("<h1>This invitation can no longer be used.</h1><p>Ask Gareth to reissue access.</p>");
    }

    @GetMapping({"/interview", "/interview/{sessionId}"})
    String interview(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                     @RequestParam(required = false) UUID revise,
                     Model model) {
        if (clerkProperties.enabled() && scopedSessionId() == null) return "redirect:/interviews";
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        model.addAttribute("view", view);
        if (view.state().equals("NOT_STARTED")) return "interview-introduction";

        var question = db.sql("select id,text,response_mode from questions where session_id=:session and active=true")
                .param("session", view.sessionId()).query((rs, row) -> new ActiveQuestion(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional();
        model.addAttribute("question", question.map(ActiveQuestion::text).orElse(null));
        model.addAttribute("questionId", question.map(ActiveQuestion::id).orElse(null));
        model.addAttribute("questionMode", question.map(ActiveQuestion::responseMode).orElse("FREE_TEXT"));
        model.addAttribute("options", question.map(q -> db.sql("select option_id,label from question_options where question_id=:id order by position")
                .param("id", q.id()).query((rs, row) -> Map.of("id", rs.getString(1), "label", rs.getString(2))).list()).orElse(List.of()));
        model.addAttribute("transcript", transcript(view.sessionId()));
        model.addAttribute("progress", progress(view.sessionId()));
        var working = view.state().equals("ACTIVE") && question.isEmpty();
        model.addAttribute("working", working);
        if (revise != null) {
            var revisionTargetExists = db.sql("""
                    select exists(select 1 from evidence
                    where id=:evidence and session_id=:session and participant_id is not null and revises is null)
                    """).param("evidence", revise).param("session", view.sessionId()).query(Boolean.class).single();
            if (!view.state().equals("ACTIVE") || working || !revisionTargetExists) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        model.addAttribute("revisionEvidenceId", revise);

        if (view.state().equals("ACTIVE")) return "interview-question";
        if (view.state().equals("PAUSED")) return "interview-paused";
        if (view.state().equals("AWAITING_CONFIRMATION")) {
            var unresolved = db.sql("""
                    select coalesce(u.owner,u.summary) from unresolved u
                    join investigation_results r on r.id=u.result_id where r.session_id=:session order by u.id
                    """).param("session", view.sessionId()).query(String.class).list();
            model.addAttribute("unresolved", unresolved);
            return "interview-confirmation";
        }
        if (view.state().equals("RUNTIME_FAILED")) return "interview-runtime-failed";
        if (view.state().equals("COMPLETED") || view.state().equals("ENDED_EARLY")) return "interview-complete";
        return "interview-unavailable";
    }

    private List<TranscriptTurn> transcript(UUID sessionId) {
        var rows = db.sql("""
                select q.id,q.sequence,q.text,e.id,e.source_type,e.exact_text,e.option_label,e.explanation,e.revises
                from questions q left join evidence e on e.question_id=q.id and e.participant_id is not null
                where q.session_id=:session and not q.active
                order by q.sequence,e.created_at,case when e.revises is null then 0 else 1 end,e.id
                """).param("session", sessionId).query((rs, row) -> new TranscriptRow(
                        rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getObject(9, UUID.class))).list();
        var turns = new LinkedHashMap<UUID, TranscriptTurn>();
        for (var row : rows) {
            var turn = turns.computeIfAbsent(row.questionId(), ignored ->
                    new TranscriptTurn(row.questionId(), row.sequence(), row.question(), new ArrayList<>()));
            if (row.evidenceId() != null) turn.responses().add(new TranscriptResponse(
                    row.evidenceId(), row.sourceType(), row.exactText(), row.optionLabel(), row.explanation(), row.revises()));
        }
        return List.copyOf(turns.values());
    }

    private ConversationProgress progress(UUID sessionId) {
        var counts = db.sql("""
                select count(*) filter(where i.required),
                       count(*) filter(where i.required and r.coverage not in ('UNADDRESSED','EXPLORING','PARTIAL')),
                       count(*) filter(where i.required and r.coverage in ('EXPLORING','PARTIAL')),
                       (select count(*) from evidence e where e.session_id=:session and e.participant_id is not null)
                from investigation_results r join investigation_items i on i.id=r.item_id
                where r.session_id=:session
                """).param("session", sessionId).query((rs, row) ->
                        new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}).single();
        var total = counts[0];
        var outcomes = counts[1];
        var exploring = counts[2];
        var responses = counts[3];
        var percent = total == 0 ? 0 : (int) Math.round(outcomes * 100.0 / total);
        String summary;
        if (total == 0) summary = "Conversation in progress";
        else if (outcomes == total) summary = "All key areas have an outcome";
        else if (outcomes > 0 && exploring > 0) summary = outcomes + " of " + total + " key areas "
                + (outcomes == 1 ? "has" : "have") + " an outcome · exploring " + exploring + " more";
        else if (exploring > 0) summary = total == 1 ? "Exploring 1 key area"
                : "Exploring " + exploring + " of " + total + " key areas";
        else summary = total == 1 ? "1 key area to explore" : total + " key areas to explore";
        return new ConversationProgress(outcomes, total, responses, percent, summary);
    }

    @GetMapping(value = {"/interview/events", "/interview/{sessionId}/events"}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    SseEmitter interviewEvents(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        var emitter = eventStream.subscribe(view.sessionId(), InterviewRouting.base(currentRequest) + "/state");
        if (!waitingForRuntime(view.sessionId())) eventStream.signal(emitter);
        return emitter;
    }

    @GetMapping({"/interview/state", "/interview/{sessionId}/state"})
    String interviewState(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                          Model model, HttpServletResponse response) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (waitingForRuntime(view.sessionId())) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
            return null;
        }
        var template = interview(token, null, model);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setHeader("Datastar-Selector", "#interview-shell");
        response.setHeader("Datastar-Mode", "outer");
        return template + " :: interviewShell";
    }

    private boolean waitingForRuntime(UUID sessionId) {
        return db.sql("""
                select s.state='ACTIVE' and not exists(
                    select 1 from questions q where q.session_id=s.id and q.active
                ) from interview_sessions s where s.id=:session
                """).param("session", sessionId).query(Boolean.class).single();
    }

    @PostMapping({"/interview/begin", "/interview/{sessionId}/begin"})
    String begin(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        transactions.executeWithoutResult(status -> {
            var changed = db.sql("update interview_sessions set state='ACTIVE',started_at=now(),revision=revision+1 where id=:id and state='NOT_STARTED'")
                    .param("id", view.sessionId()).update();
            if (changed != 1) return;
            db.sql("""
                    insert into investigation_results(id,organization_id,session_id,item_id)
                    select gen_random_uuid(),:organization,:session,id
                    from investigation_items where mission_id=:mission and version=:version
                    """).param("organization", view.organizationId()).param("session", view.sessionId())
                    .param("mission", view.missionId()).param("version", view.missionVersion()).update();
            db.sql("""
                    insert into questions(id,organization_id,session_id,item_id,sequence,text)
                    select gen_random_uuid(),:organization,:session,i.id,1,e.value
                    from mission_entries e
                    join investigation_items i on i.mission_id=e.mission_id and i.version=e.version and i.position=0
                    where e.mission_id=:mission and e.version=:version and e.kind='OPENING_QUESTION' and e.position=0
                    """).param("organization", view.organizationId()).param("session", view.sessionId())
                    .param("mission", view.missionId()).param("version", view.missionVersion()).update();
        });
        return "redirect:/interview";
    }

    @PostMapping({"/interview/answer", "/interview/{sessionId}/answer"})
    String answer(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                  @RequestParam(required = false) UUID questionId,
                  @RequestParam(required = false, defaultValue = "") String optionId,
                  @RequestParam(required = false, defaultValue = "") String answer) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (answer.length() > 10_000 || (optionId.isBlank() && answer.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an answer or choose a response");
        }
        if (optionId.isBlank()) acceptEvidence(view, questionId, "ANSWER", answer, null, null, null, null);
        else acceptStructuredResponse(view, questionId, optionId, answer);
        return "redirect:/interview";
    }

    @PostMapping({"/interview/structured-answer", "/interview/{sessionId}/structured-answer"})
    String structuredAnswer(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                            @RequestParam(required = false) UUID questionId,
                            @RequestParam String optionId,
                            @RequestParam(required = false, defaultValue = "") String explanation) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (explanation.length() > 10_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        acceptStructuredResponse(view, questionId, optionId, explanation);
        return "redirect:/interview";
    }

    private void acceptStructuredResponse(ParticipantView view, UUID questionId, String optionId, String explanation) {
        transactions.executeWithoutResult(status -> {
            var labelQuery = questionId == null
                    ? db.sql("""
                            select o.label from question_options o join questions q on q.id=o.question_id
                            where q.session_id=:session and q.active and o.option_id=:option
                            """).param("session", view.sessionId()).param("option", optionId)
                    : db.sql("""
                            select o.label from question_options o join questions q on q.id=o.question_id
                            where q.session_id=:session and q.id=:question and o.option_id=:option
                            """).param("session", view.sessionId()).param("question", questionId).param("option", optionId);
            var label = labelQuery.query(String.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
            acceptEvidenceInTransaction(view, questionId, "STRUCTURED_ANSWER",
                    explanation.isBlank() ? label : label + "\n" + explanation,
                    optionId, label, explanation, null, true);
        });
    }

    @PostMapping({"/interview/pause", "/interview/{sessionId}/pause"})
    String pause(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token) {
        changeState(token, "ACTIVE", "PAUSED");
        return "redirect:/interview";
    }

    @PostMapping({"/interview/resume", "/interview/{sessionId}/resume"})
    String resume(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token) {
        changeState(token, "PAUSED", "ACTIVE");
        return "redirect:/interview";
    }

    @PostMapping({"/interview/another-owner", "/interview/{sessionId}/another-owner"})
    String anotherOwner(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                        @RequestParam(required = false) UUID questionId,
                        @RequestParam String owner, @RequestParam String reason) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (owner.isBlank() || owner.length() > 120 || reason.isBlank() || reason.length() > 2_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        transactions.executeWithoutResult(status -> {
            var evidence = acceptEvidenceInTransaction(view, questionId, "OWNERSHIP_GAP", reason, null, null, owner, null, false);
            if (evidence == null) return;
            var resultId = db.sql("select id from investigation_results where session_id=:session and item_id=(select item_id from questions where id=:question)")
                    .param("session", view.sessionId()).param("question", evidence.questionId()).query(UUID.class).single();
            db.sql("update investigation_results set coverage='OWNERSHIP_GAP' where id=:id").param("id", resultId).update();
            db.sql("insert into unresolved values(:id,:result,:evidence,'OWNERSHIP_GAP',:summary,:reason,:owner,false)")
                    .param("id", Ids.id()).param("result", resultId).param("evidence", evidence.id())
                    .param("summary", "Another owner is needed").param("reason", reason).param("owner", owner.trim()).update();
            continueOrAwaitConfirmation(view, evidence.id());
        });
        return "redirect:/interview";
    }

    @PostMapping({"/interview/do-not-know", "/interview/{sessionId}/do-not-know"})
    String doNotKnow(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                     @RequestParam(required = false) UUID questionId,
                     @RequestParam(required = false, defaultValue = "") String explanation) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (explanation.length() > 2_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        transactions.executeWithoutResult(status -> {
            var evidence = acceptEvidenceInTransaction(view, questionId, "UNKNOWN", "I don't know", null, null, explanation, null, false);
            if (evidence == null) return;
            var resultId = db.sql("select id from investigation_results where session_id=:session and item_id=(select item_id from questions where id=:question)")
                    .param("session", view.sessionId()).param("question", evidence.questionId()).query(UUID.class).single();
            db.sql("update investigation_results set coverage='UNKNOWN' where id=:id").param("id", resultId).update();
            db.sql("insert into unresolved values(:id,:result,:evidence,'UNKNOWN','Interviewee did not know',:reason,null,false)")
                    .param("id", Ids.id()).param("result", resultId).param("evidence", evidence.id())
                    .param("reason", explanation.isBlank() ? "The answer is not known" : explanation).update();
            continueOrAwaitConfirmation(view, evidence.id());
        });
        return "redirect:/interview";
    }

    @PostMapping({"/interview/discomfort", "/interview/{sessionId}/discomfort"})
    String discomfort(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                      @RequestParam(required = false) UUID questionId) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        transactions.executeWithoutResult(status -> {
            var evidence = acceptEvidenceInTransaction(view, questionId, "UNKNOWN", "I do not want to continue this line",
                    null, null, "Interviewee discomfort", null, false);
            if (evidence == null) return;
            var resultId = db.sql("select id from investigation_results where session_id=:session and item_id=(select item_id from questions where id=:question)")
                    .param("session", view.sessionId()).param("question", evidence.questionId()).query(UUID.class).single();
            db.sql("update investigation_results set coverage='UNKNOWN' where id=:id").param("id", resultId).update();
            db.sql("insert into unresolved values(:id,:result,:evidence,'UNKNOWN','Interviewee chose not to continue this line','Interviewee discomfort',null,false)")
                    .param("id", Ids.id()).param("result", resultId).param("evidence", evidence.id()).update();
            continueOrAwaitConfirmation(view, evidence.id());
        });
        return "redirect:/interview";
    }

    @PostMapping({"/interview/clarify", "/interview/{sessionId}/clarify"})
    String clarify(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                   @RequestParam(required = false) UUID questionId) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        transactions.executeWithoutResult(status -> {
            var questionQuery = questionId == null
                    ? db.sql("select id,item_id,sequence,text,clarifies from questions where session_id=:session and active for update")
                            .param("session", view.sessionId())
                    : db.sql("select id,item_id,sequence,text,clarifies from questions where session_id=:session and id=:question and active for update")
                            .param("session", view.sessionId()).param("question", questionId);
            var question = questionQuery.query((rs, row) -> new Clarifiable(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4), rs.getObject(5, UUID.class)))
                    .optional().orElse(null);
            if (question == null) return;
            if (question.clarifies() != null) {
                var evidence = acceptEvidenceInTransaction(view, question.id(), "UNKNOWN", "The question remained unclear", null, null,
                        "Repeated clarification did not help", null, false);
                var resultId = db.sql("select id from investigation_results where session_id=:session and item_id=:item")
                        .param("session", view.sessionId()).param("item", question.itemId()).query(UUID.class).single();
                db.sql("update investigation_results set coverage='UNKNOWN' where id=:id").param("id", resultId).update();
                db.sql("insert into unresolved values(:id,:result,:evidence,'UNKNOWN','Question remained unclear','Repeated clarification did not help',null,false)")
                        .param("id", Ids.id()).param("result", resultId).param("evidence", evidence.id()).update();
                continueOrAwaitConfirmation(view, evidence.id());
            } else {
                db.sql("update questions set active=false where id=:id").param("id", question.id()).update();
                db.sql("insert into questions(id,organization_id,session_id,item_id,sequence,text,clarifies) values(:id,:organization,:session,:item,:sequence,:text,:clarifies)")
                        .param("id", Ids.id()).param("organization", view.organizationId()).param("session", view.sessionId())
                        .param("item", question.itemId()).param("sequence", question.sequence() + 1)
                        .param("text", "Let me put that another way: " + question.text()).param("clarifies", question.id()).update();
                db.sql("update interview_sessions set revision=revision+1 where id=:id").param("id", view.sessionId()).update();
            }
        });
        return "redirect:/interview";
    }

    @PostMapping({"/interview/revise", "/interview/{sessionId}/revise"})
    String revise(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token,
                  @RequestParam UUID evidenceId,
                  @RequestParam(required = false) Integer expectedRevision,
                  @RequestParam String answer,
                  org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        if (answer.isBlank() || answer.length() > 10_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var result = transactions.execute(status -> {
            // The Session and source Question are the serialisation boundary for a
            // correction. Replay detection must happen only after both authoritative
            // rows are locked, otherwise concurrent legacy retries can both append.
            var context = db.sql("""
                    select s.state,s.revision,e.question_id,e.participant_id
                    from interview_sessions s
                    join evidence e on e.session_id=s.id and e.id=:evidence and e.participant_id is not null
                    join questions q on q.id=e.question_id and q.session_id=s.id
                    where s.id=:session
                    for update of s,q
                    """).param("evidence", evidenceId).param("session", view.sessionId())
                    .query((rs, row) -> new RevisionContext(rs.getString(1), rs.getInt(2),
                            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class))).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var replayed = db.sql("select exists(select 1 from evidence where session_id=:session and revises=:evidence and exact_text=:text)")
                    .param("session", view.sessionId()).param("evidence", evidenceId).param("text", answer).query(Boolean.class).single();
            if (replayed) return RevisionResult.REPLAYED;
            if (!context.state().equals("ACTIVE")) return RevisionResult.NOOP;
            if (expectedRevision != null && context.revision() != expectedRevision) return RevisionResult.STALE;
            db.sql("update questions set active=false where session_id=:session and active").param("session", view.sessionId()).update();
            var revision = context.revision() + 1;
            db.sql("update interview_sessions set revision=:revision where id=:session")
                    .param("revision", revision).param("session", view.sessionId()).update();
            var newEvidence = Ids.id();
            db.sql("insert into evidence(id,organization_id,session_id,question_id,participant_id,source_type,exact_text,revises) values(:id,:organization,:session,:question,:participant,'REVISION',:text,:revises)")
                    .param("id", newEvidence).param("organization", view.organizationId()).param("session", view.sessionId())
                    .param("question", context.questionId()).param("participant", context.participantId()).param("text", answer).param("revises", evidenceId).update();
            db.sql("insert into runtime_runs(id,organization_id,session_id,evidence_id,expected_revision,state) values(:id,:organization,:session,:evidence,:revision,'PENDING')")
                    .param("id", Ids.id()).param("organization", view.organizationId()).param("session", view.sessionId())
                    .param("evidence", newEvidence).param("revision", revision).update();
            return RevisionResult.APPENDED;
        });
        if (result == RevisionResult.STALE) redirect.addFlashAttribute("revisionConflict", true);
        return "redirect:/interview";
    }

    @PostMapping({"/interview/confirm-completion", "/interview/{sessionId}/confirm-completion"})
    String confirmCompletion(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        var incomplete = db.sql("""
                select count(*) from investigation_results r join investigation_items i on i.id=r.item_id
                where r.session_id=:session and i.required and r.coverage in ('UNADDRESSED','EXPLORING','PARTIAL')
                """).param("session", view.sessionId()).query(Long.class).single();
        if (view.state().equals("COMPLETED")) return "redirect:/interview";
        if (!view.state().equals("AWAITING_CONFIRMATION")) return "redirect:/interview";
        if (incomplete > 0) {
            transactions.executeWithoutResult(status -> {
                var evidenceId = db.sql("""
                        select id from evidence where session_id=:session and participant_id is not null
                        order by created_at desc,id desc limit 1
                        """).param("session", view.sessionId()).query(UUID.class).optional().orElse(null);
                if (evidenceId == null) return;
                var changed = db.sql("update interview_sessions set state='ACTIVE' where id=:session and state='AWAITING_CONFIRMATION'")
                        .param("session", view.sessionId()).update();
                if (changed != 1) return;
                enqueueRuntime(view, evidenceId);
            });
            return "redirect:/interview";
        }
        db.sql("update interview_sessions set state='COMPLETED',completed_at=now(),revision=revision+1 where id=:id")
                .param("id", view.sessionId()).update();
        return "redirect:/interview";
    }

    @PostMapping({"/interview/retry", "/interview/{sessionId}/retry"})
    String retry(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        transactions.executeWithoutResult(status -> {
            var failed = db.sql("select evidence_id,expected_revision from runtime_runs where session_id=:session and state='FAILED' order by created_at desc limit 1 for update")
                    .param("session", view.sessionId()).query((rs, row) -> new FailedRun(rs.getObject(1, UUID.class), rs.getInt(2)))
                    .optional().orElse(null);
            if (failed == null) return;
            db.sql("update runtime_runs set state='SUPERSEDED' where session_id=:session and state='FAILED'")
                    .param("session", view.sessionId()).update();
            db.sql("insert into runtime_runs(id,organization_id,session_id,evidence_id,expected_revision,state) values(:id,:organization,:session,:evidence,:revision,'PENDING')")
                    .param("id", Ids.id()).param("organization", view.organizationId()).param("session", view.sessionId())
                    .param("evidence", failed.evidenceId()).param("revision", failed.expectedRevision()).update();
            db.sql("update interview_sessions set state='ACTIVE' where id=:session and state='RUNTIME_FAILED'")
                    .param("session", view.sessionId()).update();
        });
        return "redirect:/interview";
    }

    @PostMapping({"/interview/end-early", "/interview/{sessionId}/end-early"})
    String endEarly(@CookieValue(value = INTERVIEW_COOKIE, required = false) String token) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        db.sql("update interview_sessions set state='ENDED_EARLY',completed_at=now(),revision=revision+1 where id=:session and state in ('ACTIVE','PAUSED','RUNTIME_FAILED')")
                .param("session", view.sessionId()).update();
        return "redirect:/interview";
    }

    @PostMapping("/app/interviews/{sessionId}/revoke-access")
    String revokeAccess(@PathVariable UUID sessionId, Authentication authentication) {
        var owned = ownedSession(sessionId, authentication);
        transactions.executeWithoutResult(status -> {
            db.sql("update access_grants set revoked_at=now() where session_id=:session and revoked_at is null")
                    .param("session", sessionId).update();
            db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id=:session and state<>'REVOKED'")
                    .param("session", sessionId).update();
            audit(owned.organizationId(), "INVESTIGATOR", currentInvestigator.require(authentication).membershipId(),
                    "PARTICIPANT_ACCESS_REVOKED", sessionId, "SUCCEEDED");
        });
        return "redirect:/app/findings/" + sessionId;
    }

    @PostMapping("/app/interviews/{sessionId}/terminate")
    String terminate(@PathVariable UUID sessionId, Authentication authentication) {
        var owned = ownedSession(sessionId, authentication);
        transactions.executeWithoutResult(status -> {
            db.sql("update interview_sessions set state='TERMINATED',revision=revision+1 where id=:session and state<>'TERMINATED'")
                    .param("session", sessionId).update();
            db.sql("update access_grants set revoked_at=now() where session_id=:session and revoked_at is null")
                    .param("session", sessionId).update();
            db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id=:session and state<>'REVOKED'")
                    .param("session", sessionId).update();
            audit(owned.organizationId(), "INVESTIGATOR", currentInvestigator.require(authentication).membershipId(),
                    "INTERVIEW_TERMINATED", sessionId, "SUCCEEDED");
        });
        return "redirect:/app/missions/" + owned.missionId() + "/review";
    }

    @PostMapping("/test/runtime/run-once")
    @ResponseBody
    ResponseEntity<?> runRuntimeOnce(@RequestParam(required = false) UUID sessionId) {
        if (!testSupport) return ResponseEntity.notFound().build();
        var event = processRuntimeNext(sessionId);
        return event == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(Map.of("event", event));
    }

    String processRuntimeNext() {
        return processRuntimeNext(null);
    }

    private String processRuntimeNext(UUID sessionId) {
        return transactions.execute(status -> {
            var query = """
                    select r.id,r.organization_id,r.session_id,r.evidence_id,r.expected_revision,e.exact_text,r.attempts
                    from runtime_runs r join evidence e on e.id=r.evidence_id
                    where r.state='PENDING' %s order by r.created_at limit 1 for update of r skip locked
                    """.formatted(sessionId == null ? "" : "and r.session_id=:session");
            var request = db.sql(query);
            if (sessionId != null) request = request.param("session", sessionId);
            var run = request.query((rs, row) -> new PendingRun(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5), rs.getString(6), rs.getInt(7)))
                    .optional().orElse(null);
            if (run == null) return null;
            var missionVersion = db.sql("select mission_version from interview_sessions where id=:id")
                    .param("id", run.sessionId()).query(Integer.class).single();
            var checkpoint = run.sessionId() + ":" + missionVersion + ":" + run.expectedRevision();
            db.sql("insert into runtime_checkpoints values(:run,:session,:mission,:revision,'READY',:location,:checksum,now()) on conflict(run_id) do update set session_id=excluded.session_id,mission_version=excluded.mission_version,expected_revision=excluded.expected_revision,state='READY',location=excluded.location,checksum=excluded.checksum,created_at=now()")
                    .param("run", run.id()).param("session", run.sessionId()).param("mission", missionVersion)
                    .param("revision", run.expectedRevision()).param("location", "private://" + run.id()).param("checksum", Ids.sha(checkpoint)).update();
            var died = db.sql("update fault_controls set remaining=remaining-1 where kind='runtime_death' and remaining>0").update();
            if (died == 1) {
                db.sql("update runtime_runs set restart_count=restart_count+1 where id=:id and restart_count=0").param("id", run.id()).update();
                return "runtime_restarting";
            }
            var failed = db.sql("update fault_controls set remaining=remaining-1 where kind='runtime' and remaining>0").update();
            if (failed == 1) {
                var attempts = run.attempts() + 1;
                if (attempts >= 3) {
                    db.sql("update runtime_runs set state='FAILED',attempts=:attempts,error_code='MODEL_EXHAUSTED',stable_event='runtime_failed' where id=:id")
                            .param("attempts", attempts).param("id", run.id()).update();
                    db.sql("update interview_sessions set state='RUNTIME_FAILED' where id=:id and revision=:revision")
                            .param("id", run.sessionId()).param("revision", run.expectedRevision()).update();
                    return "runtime_failed";
                }
                db.sql("update runtime_runs set attempts=:attempts where id=:id").param("attempts", attempts).param("id", run.id()).update();
                return "runtime_retrying";
            }
            var revision = db.sql("select revision from interview_sessions where id=:id for update")
                    .param("id", run.sessionId()).query(Integer.class).single();
            if (revision != run.expectedRevision()) {
                db.sql("update runtime_runs set state='FAILED',error_code='STALE_REVISION',attempts=attempts+1 where id=:id")
                        .param("id", run.id()).update();
                return "runtime_failed";
            }
            var nextSequence = db.sql("select coalesce(max(sequence),0)+1 from questions where session_id=:session")
                    .param("session", run.sessionId()).query(Integer.class).single();
            if (nextSequence >= 3) {
                var coverage = run.answer().toLowerCase().contains("contradict") ? "CONFLICT" : "SUPPORTED";
                var resultId = db.sql("""
                        select r.id from investigation_results r join evidence e on e.session_id=r.session_id
                        join questions q on q.id=e.question_id and q.item_id=r.item_id
                        where r.session_id=:session and e.id=:evidence
                        """).param("session", run.sessionId()).param("evidence", run.evidenceId()).query(UUID.class).single();
                db.sql("""
                        update investigation_results set coverage=:coverage
                        where session_id=:session and item_id=(select q.item_id from evidence e join questions q on q.id=e.question_id where e.id=:evidence)
                        """).param("coverage", coverage).param("session", run.sessionId()).param("evidence", run.evidenceId()).update();
                db.sql("""
                        insert into result_evidence(result_id,evidence_id)
                        select r.id,:evidence from investigation_results r
                        join evidence e on e.session_id=r.session_id join questions q on q.id=e.question_id and q.item_id=r.item_id
                        where r.session_id=:session and e.id=:evidence on conflict do nothing
                        """).param("session", run.sessionId()).param("evidence", run.evidenceId()).update();
                var outcomeId = Ids.id();
                db.sql("insert into result_outcomes(id,result_id,coverage,category,summary) select :id,:result,:outcomeCoverage,'FACT',exact_text from evidence where id=:evidence")
                        .param("id", outcomeId).param("result", resultId)
                        .param("outcomeCoverage", coverage.equals("CONFLICT") ? "SUPPORTED" : coverage)
                        .param("evidence", run.evidenceId()).update();
                db.sql("insert into result_outcome_evidence values(:outcome,:evidence)")
                        .param("outcome", outcomeId).param("evidence", run.evidenceId()).update();
                if (coverage.equals("CONFLICT")) {
                    db.sql("""
                            with prior as (
                              select e.id,e.exact_text from evidence e join questions q on q.id=e.question_id
                              where e.session_id=:session and q.item_id=(select item_id from investigation_results where id=:result)
                                and e.id<>:current order by e.created_at,e.id limit 1
                            ), inserted as (
                              insert into result_outcomes(id,result_id,coverage,category,summary)
                              select gen_random_uuid(),:result,'SUPPORTED','FACT',exact_text from prior returning id
                            )
                            insert into result_outcome_evidence(outcome_id,evidence_id)
                            select inserted.id,prior.id from inserted cross join prior
                            """).param("session", run.sessionId()).param("result", resultId).param("current", run.evidenceId()).update();
                    var unresolvedId = Ids.id();
                    db.sql("insert into unresolved values(:id,:result,:evidence,'CONFLICT','Contradictory evidence needs review','The answers conflict',null,false)")
                            .param("id", unresolvedId).param("result", resultId).param("evidence", run.evidenceId()).update();
                    db.sql("insert into unresolved_evidence values(:unresolved,:evidence)")
                            .param("unresolved", unresolvedId).param("evidence", run.evidenceId()).update();
                }
                db.sql("update runtime_runs set state='COMPLETED',attempts=attempts+1,stable_event='completion_confirmation_ready' where id=:id")
                        .param("id", run.id()).update();
                db.sql("update runtime_checkpoints set state='COMMITTED' where run_id=:id").param("id", run.id()).update();
                db.sql("update interview_sessions set state='AWAITING_CONFIRMATION',revision=revision+1 where id=:id")
                        .param("id", run.sessionId()).update();
                return "completion_confirmation_ready";
            }
            var nextQuestion = run.answer().toLowerCase().contains("finance analyst")
                    ? "What does the finance analyst check in the source ledger?"
                    : "What happens next after that?";
            var nextQuestionId = Ids.id();
            db.sql("""
                    insert into questions(id,organization_id,session_id,item_id,sequence,text,response_mode)
                    select :id,:organization,:session,q.item_id,:sequence,:text,'YES_NO_PARTLY'
                    from evidence e join questions q on q.id=e.question_id where e.id=:evidence
                    """).param("id", nextQuestionId).param("organization", run.organizationId()).param("session", run.sessionId())
                    .param("sequence", nextSequence).param("text", nextQuestion).param("evidence", run.evidenceId()).update();
            db.sql("insert into question_options values(:question,'yes',0,'Yes'),(:question,'no',1,'No'),(:question,'partly',2,'Partly')")
                    .param("question", nextQuestionId).update();
            db.sql("update runtime_runs set state='COMPLETED',attempts=attempts+1,stable_event='question_ready' where id=:id")
                    .param("id", run.id()).update();
            db.sql("update runtime_checkpoints set state='COMMITTED' where run_id=:id").param("id", run.id()).update();
            db.sql("update interview_sessions set revision=revision+1 where id=:id").param("id", run.sessionId()).update();
            return "question_ready";
        });
    }

    private void continueOrAwaitConfirmation(ParticipantView view, UUID evidenceId) {
        var incompleteRequired = db.sql("""
                select count(*) from investigation_results r join investigation_items i on i.id=r.item_id
                where r.session_id=:session and i.required and r.coverage in ('UNADDRESSED','EXPLORING','PARTIAL')
                """).param("session", view.sessionId()).query(Long.class).single();
        if (incompleteRequired == 0) {
            db.sql("update interview_sessions set state='AWAITING_CONFIRMATION' where id=:session")
                    .param("session", view.sessionId()).update();
            return;
        }
        enqueueRuntime(view, evidenceId);
    }

    private void enqueueRuntime(ParticipantView view, UUID evidenceId) {
        var revision = db.sql("select revision from interview_sessions where id=:session")
                .param("session", view.sessionId()).query(Integer.class).single();
        db.sql("""
                insert into runtime_runs(id,organization_id,session_id,evidence_id,expected_revision,state)
                select :id,:organization,:session,:evidence,:revision,'PENDING'
                where not exists(select 1 from runtime_runs where session_id=:session and state in ('PENDING','RUNNING'))
                """).param("id", Ids.id()).param("organization", view.organizationId()).param("session", view.sessionId())
                .param("evidence", evidenceId).param("revision", revision).update();
    }

    private void changeState(String token, String from, String to) {
        var view = participant(token).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        db.sql("update interview_sessions set state=:to,revision=revision+1 where id=:id and state=:from")
                .param("to", to).param("id", view.sessionId()).param("from", from).update();
    }

    private EvidenceAccepted acceptEvidence(ParticipantView view, UUID questionId, String sourceType, String exactText,
                                            String optionId, String optionLabel, String explanation, UUID revises) {
        return transactions.execute(status -> acceptEvidenceInTransaction(
                view, questionId, sourceType, exactText, optionId, optionLabel, explanation, revises, true));
    }

    private EvidenceAccepted acceptEvidenceInTransaction(ParticipantView view, UUID questionId, String sourceType, String exactText,
                                                         String optionId, String optionLabel, String explanation,
                                                         UUID revises, boolean enqueueRuntime) {
        var turnQuery = questionId == null
                ? db.sql("""
                        select q.id,s.participant_id,s.revision
                        from questions q join interview_sessions s on s.id=q.session_id
                        where q.session_id=:session and q.active and s.state='ACTIVE'
                        for update of q,s
                        """).param("session", view.sessionId())
                : db.sql("""
                        select q.id,s.participant_id,s.revision
                        from questions q join interview_sessions s on s.id=q.session_id
                        where q.session_id=:session and q.id=:question and q.active and s.state='ACTIVE'
                        for update of q,s
                        """).param("session", view.sessionId()).param("question", questionId);
        var turn = turnQuery
                .query((rs, row) -> new AnswerTurn(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)))
                .optional().orElse(null);
        if (turn == null) return null;
        var evidenceId = Ids.id();
        var nextRevision = turn.revision() + 1;
        db.sql("""
                insert into evidence(id,organization_id,session_id,question_id,participant_id,source_type,exact_text,option_id,option_label,explanation,revises)
                values(:id,:organization,:session,:question,:participant,:source,:text,:optionId,:optionLabel,:explanation,:revises)
                """).param("id", evidenceId).param("organization", view.organizationId()).param("session", view.sessionId())
                .param("question", turn.questionId()).param("participant", turn.participantId()).param("source", sourceType)
                .param("text", exactText).param("optionId", optionId).param("optionLabel", optionLabel)
                .param("explanation", explanation).param("revises", revises).update();
        db.sql("update questions set active=false where id=:id").param("id", turn.questionId()).update();
        db.sql("update interview_sessions set revision=:revision where id=:id")
                .param("revision", nextRevision).param("id", view.sessionId()).update();
        if (enqueueRuntime) {
            db.sql("insert into runtime_runs(id,organization_id,session_id,evidence_id,expected_revision,state) values(:id,:organization,:session,:evidence,:revision,'PENDING')")
                    .param("id", Ids.id()).param("organization", view.organizationId()).param("session", view.sessionId())
                    .param("evidence", evidenceId).param("revision", nextRevision).update();
        }
        return new EvidenceAccepted(evidenceId, turn.questionId());
    }

    private UUID scopedSessionId() {
        Object variables = currentRequest.getAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables instanceof java.util.Map<?, ?> map && map.get("sessionId") instanceof String id) {
            try { return UUID.fromString(id); } catch (IllegalArgumentException invalid) { return null; }
        }
        return null;
    }

    private Optional<ParticipantView> participant(String token) {
        UUID sessionId = scopedSessionId();
        String subject = null;
        if (clerkProperties.enabled()) {
            if (sessionId == null || !(currentRequest.getUserPrincipal() instanceof Authentication authentication)) return Optional.empty();
            subject = ClerkInterviewAccess.subject(authentication);
        } else if (token == null || token.isBlank()) return Optional.empty();
        var query = db.sql("""
                select s.id,s.organization_id,s.mission_id,s.mission_version,s.state,s.revision,
                       u.display_name,o.name,mv.objective,mv.data_use,o.retention_days,o.contact_email
                from interview_sessions s
                """ + (clerkProperties.enabled()
                    ? " join interview_account_access a on a.session_id=s.id and a.organization_id=s.organization_id "
                    : " join access_grants g on s.id=g.session_id and s.organization_id=g.organization_id ") + """
                join missions m on m.id=s.mission_id
                join discoveries d on d.id=s.discovery_id
                join memberships owner on owner.id=d.owner_membership_id
                join users u on u.id=owner.user_id
                join organizations o on o.id=s.organization_id
                join mission_versions mv on mv.mission_id=s.mission_id and mv.version=s.mission_version
                where s.deleted_at is null and s.state<>'TERMINATED' and d.state='ACTIVE'
                """ + (clerkProperties.enabled()
                    ? " and a.clerk_user_id=:subject and a.revoked_at is null and s.id=:session "
                    : " and g.token_hash=:hash and g.revoked_at is null and g.expires_at>now() "));
        if (clerkProperties.enabled()) query.param("subject", subject).param("session", sessionId);
        else query.param("hash", Ids.sha(token));
        var result = query.query((rs, row) -> new ParticipantView(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                        rs.getInt(4), rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getInt(11), rs.getString(12)))
                .optional();
        if (result.isPresent() && !clerkProperties.enabled()) {
            db.sql("update access_grants set last_used_at=now() where token_hash=:hash")
                    .param("hash", Ids.sha(token)).update();
        } else if (result.isEmpty()) {
            denied("PARTICIPANT_ACCESS_DENIED", null, null);
        }
        return result;
    }

    private OwnedSession ownedSession(UUID sessionId, Authentication authentication) {
        var investigator = currentInvestigator.require(authentication);
        return db.sql("""
                select s.organization_id,s.mission_id from interview_sessions s
                join discoveries d on d.id=s.discovery_id and d.organization_id=s.organization_id
                where s.id=:session and d.owner_membership_id=:member and d.organization_id=:organization and d.state='ACTIVE'
                """).param("session", sessionId).param("member", investigator.membershipId())
                .param("organization", investigator.organizationId())
                .query((rs, row) -> new OwnedSession(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void denied(String action, UUID organizationId, UUID resourceId) {
        audit(organizationId, "ANONYMOUS", null, action, resourceId, "DENIED");
    }

    private void audit(UUID organizationId, String actorKind, UUID actorId, String action, UUID resourceId, String outcome) {
        db.sql("insert into audit_events(id,organization_id,actor_kind,actor_id,action,resource_kind,resource_id,outcome,correlation_id) values(:id,:organization,:actorKind,:actorId,:action,'INTERVIEW_SESSION',:resource,:outcome,:correlation)")
                .param("id", Ids.id()).param("organization", organizationId).param("actorKind", actorKind).param("actorId", actorId)
                .param("action", action).param("resource", resourceId).param("outcome", outcome).param("correlation", Ids.id()).update();
    }

    public record CreatedInvitation(UUID missionId, String recipient, String link) {}
    private record ApprovedMission(UUID discoveryId, UUID organizationId) {}
    private record ExistingSession(UUID id, UUID participantId, String state, int missionVersion, String name, String email) {}
    private record Redeemable(UUID id, UUID organizationId, UUID sessionId) {}
    private record AnswerTurn(UUID questionId, UUID participantId, int revision) {}
    private record PendingRun(UUID id, UUID organizationId, UUID sessionId, UUID evidenceId,
                              int expectedRevision, String answer, int attempts) {}
    private record FailedRun(UUID evidenceId, int expectedRevision) {}
    private record ActiveQuestion(UUID id, String text, String responseMode) {}
    private record TranscriptRow(UUID questionId, int sequence, String question, UUID evidenceId,
                                 String sourceType, String exactText, String optionLabel, String explanation,
                                 UUID revises) {}
    public record TranscriptTurn(UUID questionId, int sequence, String question,
                                 List<TranscriptResponse> responses) {}
    public record TranscriptResponse(UUID id, String sourceType, String exactText, String optionLabel,
                                     String explanation, UUID revises) {
        public String status() {
            return switch (sourceType) {
                case "REVISION" -> "Correction · saved";
                case "UNKNOWN" -> "I don’t know · saved";
                case "OWNERSHIP_GAP" -> "Another owner · saved";
                default -> "Answer accepted · saved";
            };
        }
    }
    public record ConversationProgress(long outcomes, long total, long responses, int percent, String summary) {}
    private record Clarifiable(UUID id, UUID itemId, int sequence, String text, UUID clarifies) {}
    private record RevisionContext(String state, int revision, UUID questionId, UUID participantId) {}
    private enum RevisionResult { APPENDED, REPLAYED, STALE, NOOP }
    private record EvidenceAccepted(UUID id, UUID questionId) {}
    private record OwnedSession(UUID organizationId, UUID missionId) {}
    public record ParticipantView(UUID sessionId, UUID organizationId, UUID missionId, int missionVersion,
                                  String state, int revision, String investigator, String organization, String objective,
                                  String dataUse, int retentionDays, String contactEmail) {}
}
