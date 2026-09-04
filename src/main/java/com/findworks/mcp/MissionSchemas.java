package com.findworks.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

final class MissionSchemas {
    private MissionSchemas() {}

    static JsonNode submission(ObjectMapper mapper, boolean create) {
        var mission = object(mapper, Map.ofEntries(
                Map.entry("objective", text(mapper, 4_000)),
                Map.entry("desired_outcome", text(mapper, 4_000)),
                Map.entry("intended_interviewee_role", text(mapper, 4_000)),
                Map.entry("intended_interviewee_relevance", text(mapper, 4_000)),
                Map.entry("investigation_items", array(mapper, object(mapper, Map.of(
                        "knowledge_gap", text(mapper, 4_000),
                        "why_it_matters", text(mapper, 4_000),
                        "priority", text(mapper, 4_000),
                        "required", type(mapper, "boolean"),
                        "relevant_context", text(mapper, 4_000),
                        "sufficient_evidence", array(mapper, text(mapper, 4_000), 1, 50)),
                        List.of("knowledge_gap", "why_it_matters", "priority", "required", "relevant_context", "sufficient_evidence")), 1, 50)),
                Map.entry("shared_context", array(mapper, text(mapper, 4_000), 0, 100)),
                Map.entry("boundaries", array(mapper, text(mapper, 4_000), 0, 100)),
                Map.entry("prohibited_topics", array(mapper, text(mapper, 4_000), 0, 100)),
                Map.entry("terminology", array(mapper, object(mapper,
                        Map.of("term", text(mapper, 4_000), "meaning", text(mapper, 4_000)),
                        List.of("term", "meaning")), 0, 100)),
                Map.entry("opening_questions", array(mapper, text(mapper, 4_000), 1, 50)),
                Map.entry("completion_criteria", array(mapper, text(mapper, 4_000), 1, 50)),
                Map.entry("expected_commitment_minutes", integer(mapper, 5, 120)),
                Map.entry("data_use_summary", text(mapper, 4_000))),
                List.of("objective", "desired_outcome", "intended_interviewee_role", "intended_interviewee_relevance",
                        "investigation_items", "shared_context", "boundaries", "prohibited_topics", "terminology",
                        "opening_questions", "completion_criteria", "expected_commitment_minutes", "data_use_summary"));
        var origin = object(mapper, Map.of(
                "pointer", text(mapper, 500),
                "authority", enumeration(mapper, "INVESTIGATOR_STATEMENT", "CONFIRMED_AGENT_PROPOSAL")),
                List.of("pointer", "authority"));
        var reference = object(mapper, Map.of(
                "kind", enumeration(mapper, "repository_file", "issue"),
                "locator", text(mapper, 500),
                "line_start", integer(mapper, 1, Integer.MAX_VALUE),
                "line_end", integer(mapper, 1, Integer.MAX_VALUE),
                "revision", text(mapper, 500)), List.of("kind", "locator"));
        var confirmations = object(mapper, Map.of(
                "exact_payload_reviewed", constant(mapper, true),
                "intentional_sharing", constant(mapper, true),
                "no_recipient_or_private_context", constant(mapper, true),
                "material_agent_proposals_confirmed", constant(mapper, true)),
                List.of("exact_payload_reviewed", "intentional_sharing", "no_recipient_or_private_context", "material_agent_proposals_confirmed"));
        var properties = new LinkedHashMap<String, JsonNode>();
        properties.put("contract_version", constant(mapper, 1));
        properties.put("submission_id", format(mapper, "uuid"));
        if (create) {
            properties.put("discovery_title", text(mapper, 4_000));
            properties.put("discovery_objective", text(mapper, 4_000));
        } else properties.put("discovery_id", format(mapper, "uuid"));
        properties.put("mission", mission);
        properties.put("origins", array(mapper, origin, 1, 500));
        properties.put("project_references", array(mapper, reference, 0, 100));
        properties.put("confirmations", confirmations);
        var required = new java.util.ArrayList<>(List.of("contract_version", "submission_id", "mission", "origins", "project_references", "confirmations"));
        if (create) required.addAll(List.of("discovery_title", "discovery_objective")); else required.add("discovery_id");
        var root = object(mapper, properties, required);
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        return root;
    }

    private static ObjectNode object(ObjectMapper mapper, Map<String, ? extends JsonNode> properties, List<String> required) {
        var node = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var propertyNode = mapper.createObjectNode();
        properties.forEach(propertyNode::set);
        node.set("properties", propertyNode);
        var requiredNode = mapper.createArrayNode();
        required.forEach(requiredNode::add);
        node.set("required", requiredNode);
        return node;
    }

    private static ObjectNode type(ObjectMapper mapper, String type) { return mapper.createObjectNode().put("type", type); }
    private static ObjectNode text(ObjectMapper mapper, int max) { return type(mapper, "string").put("minLength", 1).put("maxLength", max); }
    private static ObjectNode integer(ObjectMapper mapper, int min, int max) { return type(mapper, "integer").put("minimum", min).put("maximum", max); }
    private static ObjectNode array(ObjectMapper mapper, JsonNode items, int min, int max) { return type(mapper, "array").set("items", items).put("minItems", min).put("maxItems", max); }
    private static ObjectNode format(ObjectMapper mapper, String format) { return type(mapper, "string").put("format", format); }
    private static ObjectNode constant(ObjectMapper mapper, int value) { return mapper.createObjectNode().put("const", value); }
    private static ObjectNode constant(ObjectMapper mapper, boolean value) { return mapper.createObjectNode().put("const", value); }
    private static ObjectNode enumeration(ObjectMapper mapper, String... values) { var node = mapper.createObjectNode(); var options = mapper.createArrayNode(); for (var value : values) options.add(value); return node.set("enum", options); }
}
