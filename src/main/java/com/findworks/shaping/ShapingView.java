package com.findworks.shaping;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShapingView(UUID sessionId, List<Message> messages, String status, Event event, Proposal proposal) {

    public static ShapingView empty() {
        return new ShapingView(null, List.of(), "ready", null, null);
    }

    public record Message(String author, String content, Instant createdAt) {}

    public record Event(String type, String eventId, String question) {}

    public record Proposal(
            UUID id,
            String status,
            Text objective,
            Text desiredOutcome,
            Text intendedInterviewee,
            Text intervieweeRelevance,
            List<Context> contexts,
            List<Boundary> boundaries,
            List<Term> terminology,
            List<OpeningQuestion> openingQuestions,
            Text completionCriteria,
            Text expectedCommitment,
            Text dataUseSummary,
            List<InvestigationItem> investigationItems,
            List<Ambiguity> unresolvedAmbiguities) {}

    public record Text(String value, String provenance) {}

    public record Context(String visibility, String content, String provenance) {}

    public record Boundary(String kind, String content, String provenance) {}

    public record Term(String term, String meaning, String provenance) {}

    public record OpeningQuestion(String question, String provenance) {}

    public record InvestigationItem(
            String knowledgeGap,
            String importance,
            String priority,
            String relevantContext,
            boolean required,
            List<Outcome> allowedOutcomes,
            String provenance) {}

    public record Outcome(String kind, String provenance) {}

    public record Ambiguity(String content, Integer representedByItemPosition, String provenance) {}
}
