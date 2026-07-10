package com.modelgate.common.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatMessage(@NotBlank String role, @NotBlank String content) {
}
