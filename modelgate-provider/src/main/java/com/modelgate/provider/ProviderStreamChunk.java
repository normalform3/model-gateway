package com.modelgate.provider;

import com.modelgate.common.chat.Usage;

public record ProviderStreamChunk(String content, boolean done, Usage usage) {
    public static ProviderStreamChunk content(String content) {
        return new ProviderStreamChunk(content, false, null);
    }

    public static ProviderStreamChunk done(Usage usage) {
        return new ProviderStreamChunk("", true, usage);
    }
}
