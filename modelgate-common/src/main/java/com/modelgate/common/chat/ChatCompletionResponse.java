package com.modelgate.common.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ChatCompletionResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage
) {
    public record Choice(int index, ChatMessage message, @JsonProperty("finish_reason") String finishReason) {
    }
}
