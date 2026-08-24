package com.findworks.shaping;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
class PiShapingAdapter {

    private final ShapingTurnRunner runtime;
    private final ObjectMapper json;

    PiShapingAdapter(ShapingTurnRunner runtime, ObjectMapper json) {
        this.runtime = runtime;
        this.json = json;
    }

    Turn followUp(ShapingRepository.Context context) throws Exception {
        var submission = runtime.runShaping(context);
        var question = submission.path("question").asText(null);
        var proposal = submission.hasNonNull("proposal")
                ? json.treeToValue(submission.path("proposal"), MissionProposal.class) : null;
        if ((question == null) == (proposal == null)) {
            throw new IllegalArgumentException("Pi returned an invalid shaping result.");
        }
        return new Turn(question, proposal);
    }

    record Turn(String question, MissionProposal proposal) {}
}
