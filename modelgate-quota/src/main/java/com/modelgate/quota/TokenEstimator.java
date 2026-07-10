package com.modelgate.quota;

import com.modelgate.common.chat.ChatCompletionRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {
    private final int defaultMaxOutputTokens;

    public TokenEstimator(@Value("${modelgate.quota.default-max-output-tokens:512}") int defaultMaxOutputTokens) {
        this.defaultMaxOutputTokens = defaultMaxOutputTokens;
    }

    public int estimateInputTokens(ChatCompletionRequest request) {
        int chars = request.messages().stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        return Math.max(1, (int) Math.ceil(chars / 4.0));
    }

    public int maxOutputTokens(ChatCompletionRequest request) {
        if (request.maxTokens() == null || request.maxTokens() <= 0) {
            return defaultMaxOutputTokens;
        }
        return request.maxTokens();
    }
}
