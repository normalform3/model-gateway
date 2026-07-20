package com.modelgate.common.auth;

import com.modelgate.common.api.AuthDtos.ConsoleIdentity;

/** Current, database-derived console authority. It is intentionally not trusted from JWT claims. */
public record ConsolePrincipal(long userId, String name, String email, String role, Long teamId, Long memberId,
                               boolean passwordChangeRequired, String sessionId) {
    public ConsoleIdentity identity() { return new ConsoleIdentity(userId, name, email, role, teamId, memberId, passwordChangeRequired); }
    public boolean isPlatformAdmin() { return "PLATFORM_ADMIN".equals(role); }
    public boolean isTeamAdmin() { return "TEAM_ADMIN".equals(role); }
    public boolean isDeveloper() { return "DEVELOPER".equals(role); }
    public boolean isUnassigned() { return "UNASSIGNED".equals(role); }
}
