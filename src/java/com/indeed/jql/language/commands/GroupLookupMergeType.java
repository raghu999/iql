package com.indeed.jql.language.commands;

import com.fasterxml.jackson.databind.JsonNode;

public enum GroupLookupMergeType implements Command {
    SumAll,
    TakeTheOneUniqueValue,
    FailIfPresent;

    public static GroupLookupMergeType parseJson(JsonNode jsonNode) {
        if (!jsonNode.isTextual()) {
            throw new IllegalArgumentException("GroupLookupMergeType.parseJson argument must be a text node!");
        }
        final String jsonText = jsonNode.textValue();
        for (final GroupLookupMergeType value : GroupLookupMergeType.values()) {
            if (value.name().equalsIgnoreCase(jsonText)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown GroupLookupMergeType: [" + jsonText + "]");
    }
}
