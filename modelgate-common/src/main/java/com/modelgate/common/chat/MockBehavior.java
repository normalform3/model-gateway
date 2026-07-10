package com.modelgate.common.chat;

public record MockBehavior(String mode, Long delayMs, Integer inputTokens, Integer outputTokens) {
    public boolean mode(String expected) {
        return expected.equalsIgnoreCase(mode == null ? "" : mode);
    }
}
