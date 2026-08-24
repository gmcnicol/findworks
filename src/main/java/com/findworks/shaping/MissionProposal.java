package com.findworks.shaping;

import java.util.List;
import java.util.UUID;

record MissionProposal(
        SourcedText objective,
        SourcedText desiredOutcome,
        SourcedText intendedInterviewee,
        SourcedText intervieweeRelevance,
        List<ContextEntry> contexts,
        List<Boundary> boundaries,
        List<Term> terminology,
        List<OpeningQuestion> openingQuestions,
        SourcedText completionCriteria,
        SourcedText expectedCommitment,
        SourcedText dataUseSummary,
        List<InvestigationItem> investigationItems,
        List<Ambiguity> unresolvedAmbiguities) {

    record Source(String kind, List<UUID> messageIds) {}

    record SourcedText(String value, Source source) {}

    record ContextEntry(String visibility, String content, Source source) {}

    record Boundary(String kind, String content, Source source) {}

    record Term(String term, String meaning, Source source) {}

    record OpeningQuestion(String question, Source source) {}

    record InvestigationItem(
            String knowledgeGap,
            String importance,
            String priority,
            String relevantContext,
            boolean required,
            List<AllowedOutcome> allowedOutcomes,
            Source source) {}

    record AllowedOutcome(String kind, Source source) {}

    record Ambiguity(String content, Integer representedByItemPosition, Source source) {}
}
