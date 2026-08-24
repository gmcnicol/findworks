package com.findworks.shaping;

import tools.jackson.databind.JsonNode;

public interface ShapingTurnRunner {
    JsonNode runShaping(ShapingRepository.Context context) throws Exception;
}
