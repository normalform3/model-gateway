package com.modelgate.common.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ChatCompletionRequest(
        @NotBlank String model,
        @NotEmpty @Valid List<ChatMessage> messages,
        Boolean stream,
        @JsonProperty("max_tokens") Integer maxTokens,
        MockBehavior mock
) {
    public boolean streamEnabled() {
        return Boolean.TRUE.equals(stream);
    }
}
