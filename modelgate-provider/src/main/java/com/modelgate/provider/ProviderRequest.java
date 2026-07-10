package com.modelgate.provider;

import com.modelgate.common.chat.ChatCompletionRequest;

public record ProviderRequest(
        String requestId,
        String logicalModel,
        String actualModel,
        ChatCompletionRequest originalRequest
) {
}
