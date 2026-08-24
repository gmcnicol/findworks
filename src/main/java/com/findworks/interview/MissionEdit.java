package com.findworks.interview;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.util.MultiValueMap;

record MissionEdit(
        String objective,
        String desiredOutcome,
        String intendedInterviewee,
        String intervieweeRelevance,
        List<Context> contexts,
        List<Boundary> boundaries,
        List<Term> terminology,
        List<String> openingQuestions,
        String completionCriteria,
        String expectedCommitment,
        String dataUseSummary,
        List<Item> investigationItems,
        List<Ambiguity> ambiguities) {

    static MissionEdit from(MultiValueMap<String, String> form) {
        var items = items(form);
        return new MissionEdit(
                value(form, "objective"), value(form, "desiredOutcome"),
                value(form, "intendedInterviewee"), value(form, "intervieweeRelevance"),
                contexts(form), boundaries(form), terms(value(form, "terminology")),
                lines(value(form, "openingQuestions")), value(form, "completionCriteria"),
                value(form, "expectedCommitment"), value(form, "dataUseSummary"), items,
                ambiguities(value(form, "ambiguities"), items.size()));
    }

    private static List<Context> contexts(MultiValueMap<String, String> form) {
        var result = new ArrayList<Context>();
        lines(value(form, "sharedContext")).forEach(text -> result.add(new Context("shared", text)));
        lines(value(form, "privateContext")).forEach(text -> result.add(new Context("private", text)));
        return List.copyOf(result);
    }

    private static List<Boundary> boundaries(MultiValueMap<String, String> form) {
        var result = new ArrayList<Boundary>();
        lines(value(form, "boundaries")).forEach(text -> result.add(new Boundary("boundary", text)));
        lines(value(form, "prohibitedTopics")).forEach(text -> result.add(new Boundary("prohibited_topic", text)));
        return List.copyOf(result);
    }

    private static List<Term> terms(String input) {
        return lines(input).stream().map(line -> {
            var separator = line.indexOf('|');
            if (separator < 1 || separator == line.length() - 1) {
                throw new IllegalArgumentException("Write each term as ‘term | meaning’.");
            }
            return new Term(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }).toList();
    }

    private static List<Item> items(MultiValueMap<String, String> form) {
        var actions = form.getOrDefault("itemAction", List.of());
        var positions = form.getOrDefault("itemPosition", List.of());
        var gaps = form.getOrDefault("itemGap", List.of());
        var importance = form.getOrDefault("itemImportance", List.of());
        var priorities = form.getOrDefault("itemPriority", List.of());
        var contexts = form.getOrDefault("itemContext", List.of());
        var required = form.getOrDefault("itemRequired", List.of());
        var outcomes = form.getOrDefault("itemOutcomes", List.of());
        var size = actions.size();
        if (size != positions.size() || size != gaps.size() || size != importance.size()
                || size != priorities.size() || size != contexts.size() || size != required.size()
                || size != outcomes.size()) {
            throw new IllegalArgumentException("Investigation Item fields do not match.");
        }
        var result = new ArrayList<PositionedItem>();
        var seen = new HashSet<Integer>();
        for (var index = 0; index < size; index++) {
            if ("remove".equals(actions.get(index))
                    || ("add".equals(actions.get(index)) && gaps.get(index).isBlank())) {
                continue;
            }
            try {
                var position = Integer.parseInt(positions.get(index));
                if (position < 1 || !seen.add(position)) {
                    throw new IllegalArgumentException("Investigation Item positions must be unique positive numbers.");
                }
                result.add(new PositionedItem(position, new Item(
                        gaps.get(index).trim(), importance.get(index).trim(), priorities.get(index),
                        contexts.get(index).trim(), Boolean.parseBoolean(required.get(index)),
                        List.of(outcomes.get(index).split(",")).stream().map(String::trim)
                                .filter(value -> !value.isEmpty()).toList())));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Investigation Item positions must be unique positive numbers.");
            }
        }
        return result.stream().sorted(Comparator.comparingInt(PositionedItem::position))
                .map(PositionedItem::item).toList();
    }

    private static List<Ambiguity> ambiguities(String input, int itemCount) {
        return lines(input).stream().map(line -> {
            var separator = line.indexOf('|');
            if (separator < 0) {
                return new Ambiguity(line, null);
            }
            var reference = line.substring(0, separator).trim();
            var content = line.substring(separator + 1).trim();
            if (content.isEmpty()) {
                throw new IllegalArgumentException("Each ambiguity needs text.");
            }
            if (reference.isEmpty() || "open".equalsIgnoreCase(reference)) {
                return new Ambiguity(content, null);
            }
            try {
                var position = Integer.parseInt(reference) - 1;
                if (position < 0 || position >= itemCount) {
                    throw new IllegalArgumentException("Ambiguity references an unknown Investigation Item.");
                }
                return new Ambiguity(content, position);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Start an ambiguity with an Investigation Item number or ‘open’.");
            }
        }).toList();
    }

    private static List<String> lines(String input) {
        return input.lines().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static String value(MultiValueMap<String, String> form, String name) {
        var value = form.getFirst(name);
        return value == null ? "" : value.trim();
    }

    record Context(String visibility, String content) {}
    record Boundary(String kind, String content) {}
    record Term(String term, String meaning) {}
    record Item(String knowledgeGap, String importance, String priority, String relevantContext,
            boolean required, List<String> allowedOutcomes) {}
    record Ambiguity(String content, Integer representedByItemPosition) {}
    private record PositionedItem(int position, Item item) {}
}
