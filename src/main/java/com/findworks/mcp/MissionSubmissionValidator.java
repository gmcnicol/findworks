package com.findworks.mcp;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

final class MissionSubmissionValidator {

    private static final Set<String> ROOT = Set.of(
            "contract_version", "submission_id", "discovery_title", "discovery_objective",
            "discovery_id", "mission", "origins", "project_references", "confirmations");
    private static final Set<String> MISSION = Set.of(
            "objective", "desired_outcome", "intended_interviewee_role", "intended_interviewee_relevance",
            "investigation_items", "shared_context", "boundaries", "prohibited_topics", "terminology",
            "opening_questions", "completion_criteria", "expected_commitment_minutes", "data_use_summary");
    private static final Set<String> ITEM = Set.of(
            "knowledge_gap", "why_it_matters", "priority", "required", "relevant_context", "sufficient_evidence");
    private static final Set<String> TERM = Set.of("term", "meaning");
    private static final Set<String> ORIGIN = Set.of("pointer", "authority");
    private static final Set<String> REFERENCE = Set.of("kind", "locator", "line_start", "line_end", "revision");
    private static final Set<String> CONFIRMATION = Set.of(
            "exact_payload_reviewed", "intentional_sharing", "no_recipient_or_private_context",
            "material_agent_proposals_confirmed");
    private static final Set<String> AUTHORITIES = Set.of(
            "INVESTIGATOR_STATEMENT", "CONFIRMED_AGENT_PROPOSAL");

    private MissionSubmissionValidator() {}

    static List<Map<String, String>> validate(JsonNode input, boolean create) {
        var violations = new ArrayList<Map<String, String>>();
        unknown(input, ROOT, "", violations);
        if (input.path("contract_version").asInt() != 1) {
            add(violations, "/contract_version", "must equal 1");
        }
        try {
            UUID.fromString(input.path("submission_id").asText());
        } catch (Exception ignored) {
            add(violations, "/submission_id", "must be a UUID");
        }
        if (create) {
            text(input, "discovery_title", "", violations);
            text(input, "discovery_objective", "", violations);
            if (input.has("discovery_id")) add(violations, "/discovery_id", "is not allowed for this tool");
        } else {
            uuid(input, "discovery_id", "", violations);
            if (input.has("discovery_title")) add(violations, "/discovery_title", "is not allowed for this tool");
            if (input.has("discovery_objective")) add(violations, "/discovery_objective", "is not allowed for this tool");
        }

        var mission = input.path("mission");
        if (!mission.isObject()) {
            add(violations, "/mission", "must be an object");
        } else {
            unknown(mission, MISSION, "/mission", violations);
            for (var field : List.of("objective", "desired_outcome", "intended_interviewee_role",
                    "intended_interviewee_relevance", "data_use_summary")) {
                text(mission, field, "/mission", violations);
            }
            stringArray(mission, "shared_context", false, violations);
            stringArray(mission, "boundaries", false, violations);
            stringArray(mission, "prohibited_topics", false, violations);
            stringArray(mission, "opening_questions", true, violations);
            stringArray(mission, "completion_criteria", true, violations);
            items(mission.path("investigation_items"), violations);
            terms(mission.path("terminology"), violations);
            var minutes = mission.path("expected_commitment_minutes");
            if (!minutes.isIntegralNumber() || minutes.asInt() < 5 || minutes.asInt() > 120) {
                add(violations, "/mission/expected_commitment_minutes", "must be an integer from 5 through 120");
            }
        }

        var boundPointers = origins(input.path("origins"), violations);
        var requiredPointers = requiredOriginPointers(input, create);
        if (!boundPointers.containsAll(requiredPointers)) {
            add(violations, "/origins", "must bind every semantic field and list entry");
        }
        references(input.path("project_references"), violations);
        confirmations(input.path("confirmations"), violations);
        return violations;
    }

    private static void items(JsonNode items, List<Map<String, String>> violations) {
        if (!items.isArray() || items.isEmpty()) {
            add(violations, "/mission/investigation_items", "must contain at least one item");
            return;
        }
        if (items.size() > 50) add(violations, "/mission/investigation_items", "must contain no more than 50 items");
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var path = "/mission/investigation_items/" + index;
            if (!item.isObject()) {
                add(violations, path, "must be an object");
                continue;
            }
            unknown(item, ITEM, path, violations);
            for (var field : List.of("knowledge_gap", "why_it_matters", "priority", "relevant_context")) {
                text(item, field, path, violations);
            }
            if (!item.path("required").isBoolean()) add(violations, path + "/required", "must be a boolean");
            var sufficient = item.path("sufficient_evidence");
            if (!sufficient.isArray() || sufficient.isEmpty()) {
                add(violations, path + "/sufficient_evidence", "must contain at least one entry");
            }
        }
    }

    private static void terms(JsonNode terms, List<Map<String, String>> violations) {
        if (!terms.isArray()) {
            add(violations, "/mission/terminology", "must be an array, including when empty");
            return;
        }
        for (int index = 0; index < terms.size(); index++) {
            var term = terms.get(index);
            var path = "/mission/terminology/" + index;
            unknown(term, TERM, path, violations);
            text(term, "term", path, violations);
            text(term, "meaning", path, violations);
        }
    }

    private static Set<String> origins(JsonNode origins, List<Map<String, String>> violations) {
        var pointers = new HashSet<String>();
        if (!origins.isArray()) {
            add(violations, "/origins", "must be an array");
            return pointers;
        }
        for (int index = 0; index < origins.size(); index++) {
            var origin = origins.get(index);
            var path = "/origins/" + index;
            unknown(origin, ORIGIN, path, violations);
            var pointer = origin.path("pointer").asText();
            if (pointer.isBlank() || !pointer.startsWith("/")) add(violations, path + "/pointer", "must be a JSON Pointer");
            else if (!pointers.add(pointer)) add(violations, path + "/pointer", "must be unique");
            if (!AUTHORITIES.contains(origin.path("authority").asText())) {
                add(violations, path + "/authority", "must name a supported authority");
            }
        }
        return pointers;
    }

    private static Set<String> requiredOriginPointers(JsonNode input, boolean create) {
        var pointers = new HashSet<String>();
        if (create) {
            pointers.add("/discovery_title");
            pointers.add("/discovery_objective");
        }
        for (var field : List.of("objective", "desired_outcome", "intended_interviewee_role",
                "intended_interviewee_relevance", "expected_commitment_minutes", "data_use_summary")) {
            pointers.add("/mission/" + field);
        }
        var mission = input.path("mission");
        for (var field : List.of("investigation_items", "shared_context", "boundaries", "prohibited_topics",
                "terminology", "opening_questions", "completion_criteria")) {
            var values = mission.path(field);
            for (int index = 0; index < values.size(); index++) pointers.add("/mission/" + field + "/" + index);
        }
        return pointers;
    }

    private static void references(JsonNode references, List<Map<String, String>> violations) {
        if (!references.isArray()) {
            add(violations, "/project_references", "must be an array");
            return;
        }
        for (int index = 0; index < references.size(); index++) {
            var reference = references.get(index);
            var path = "/project_references/" + index;
            unknown(reference, REFERENCE, path, violations);
            var kind = reference.path("kind").asText();
            var locator = reference.path("locator").asText();
            if (!Set.of("repository_file", "issue").contains(kind)) add(violations, path + "/kind", "must be repository_file or issue");
            if (locator.isBlank() || locator.length() > 500 || locator.contains("\n")) add(violations, path + "/locator", "must be a bounded locator without source content");
            if (kind.equals("repository_file") && (locator.startsWith("/") || locator.contains(".."))) add(violations, path + "/locator", "must be a relative safe path");
            if (kind.equals("issue")) {
                try {
                    var uri = URI.create(locator);
                    if (!Set.of("http", "https").contains(uri.getScheme())) throw new IllegalArgumentException();
                } catch (Exception ignored) {
                    add(violations, path + "/locator", "must be an HTTP issue locator");
                }
            }
        }
    }

    private static void confirmations(JsonNode confirmations, List<Map<String, String>> violations) {
        if (!confirmations.isObject()) {
            add(violations, "/confirmations", "must be an object");
            return;
        }
        unknown(confirmations, CONFIRMATION, "/confirmations", violations);
        for (var confirmation : CONFIRMATION) {
            if (!confirmations.path(confirmation).asBoolean()) {
                add(violations, "/confirmations/" + confirmation, "must be true");
            }
        }
    }

    private static void stringArray(JsonNode mission, String field, boolean nonEmpty,
                                    List<Map<String, String>> violations) {
        var values = mission.path(field);
        var path = "/mission/" + field;
        if (!values.isArray() || (nonEmpty && values.isEmpty())) {
            add(violations, path, nonEmpty ? "must contain at least one entry" : "must be an array, including when empty");
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            if (!values.get(index).isString() || values.get(index).asText().isBlank()) {
                add(violations, path + "/" + index, "must be non-blank text");
            }
        }
    }

    private static void text(JsonNode parent, String field, String prefix, List<Map<String, String>> violations) {
        var value = parent.path(field);
        if (!value.isString() || value.asText().isBlank() || value.asText().length() > 4_000) {
            add(violations, prefix + "/" + field, "must be non-blank text of at most 4000 characters");
        }
    }

    private static void uuid(JsonNode parent, String field, String prefix, List<Map<String, String>> violations) {
        try {
            UUID.fromString(parent.path(field).asText());
        } catch (Exception ignored) {
            add(violations, prefix + "/" + field, "must be a UUID");
        }
    }

    private static void unknown(JsonNode object, Set<String> allowed, String prefix,
                                List<Map<String, String>> violations) {
        if (!object.isObject()) return;
        for (var name : object.propertyNames()) {
            if (!allowed.contains(name)) add(violations, prefix + "/" + escape(name), "is not allowed");
        }
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void add(List<Map<String, String>> violations, String pointer, String message) {
        violations.add(Map.of("pointer", pointer, "message", message));
    }
}
