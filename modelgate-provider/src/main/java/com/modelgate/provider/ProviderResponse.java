package com.modelgate.provider;

import com.modelgate.common.chat.ChatMessage;
import com.modelgate.common.chat.Usage;

public record ProviderResponse(ChatMessage message, Usage usage) {
}
