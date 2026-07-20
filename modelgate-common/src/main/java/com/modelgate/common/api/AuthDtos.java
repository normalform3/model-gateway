package com.modelgate.common.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contracts for the browser-only control-plane authentication flow. */
public final class AuthDtos {
    private AuthDtos() { }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) { }
    public record DevelopmentCredentialsStatus(boolean enabled) { }
    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 12, max = 72) String newPassword) { }
    public record ConsoleIdentity(long userId, String name, String email, String role,
                                  Long teamId, Long memberId, boolean passwordChangeRequired) { }
    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds,
                                ConsoleIdentity identity) { }
}
