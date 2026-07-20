package com.modelgate.infrastructure.db;

import com.modelgate.common.auth.ConsolePrincipal;
import com.modelgate.common.auth.DevelopmentAccountNames;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class ConsoleAuthRepository {
    private final JdbcTemplate jdbc;

    public ConsoleAuthRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Account> findEnabledAccountByEmail(String email) {
        try {
            Account account = jdbc.queryForObject("""
                    SELECT id, name, email, password_hash, password_change_required, enabled
                    FROM platform_user WHERE email = ?
                    """, (rs, row) -> new Account(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                    rs.getString("password_hash"), rs.getInt("password_change_required") == 1,
                    rs.getInt("enabled") == 1), email);
            return Optional.ofNullable(account);
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    public Optional<ConsolePrincipal> findActivePrincipal(long userId, String sessionId) {
        try {
            ConsolePrincipal principal = jdbc.queryForObject("""
                    SELECT u.id, u.name, u.email, u.password_change_required, u.platform_admin,
                           m.id member_id, t.id active_team_id, m.role member_role
                    FROM auth_session s
                    JOIN platform_user u ON u.id = s.user_id AND u.enabled = 1
                    LEFT JOIN team_member m ON m.user_id = u.id AND m.enabled = 1
                    LEFT JOIN team t ON t.id = m.team_id AND t.enabled = 1
                    WHERE s.id = ? AND s.user_id = ? AND s.revoked_at IS NULL AND s.expires_at > NOW()
                    """, (rs, row) -> {
                boolean platformAdmin = rs.getInt("platform_admin") == 1;
                String memberRole = rs.getString("member_role");
                Long activeTeamId = nullableLong(rs.getObject("active_team_id"));
                String role = platformAdmin ? "PLATFORM_ADMIN" : activeTeamId != null && "OWNER".equals(memberRole) ? "TEAM_ADMIN"
                        : activeTeamId != null && "MEMBER".equals(memberRole) ? "DEVELOPER" : "UNASSIGNED";
                if (platformAdmin && memberRole != null) return null;
                Long memberId = "UNASSIGNED".equals(role) ? null : nullableLong(rs.getObject("member_id"));
                Long teamId = activeTeamId;
                return new ConsolePrincipal(rs.getLong("id"), rs.getString("name"), rs.getString("email"), role,
                        teamId, memberId, rs.getInt("password_change_required") == 1, sessionId);
            }, sessionId, userId);
            return Optional.ofNullable(principal);
        } catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
    }

    @Transactional
    public void createSession(String sessionId, long userId, String refreshHash, OffsetDateTime expiresAt) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        jdbc.update("""
                INSERT INTO auth_session(id, user_id, refresh_token_hash, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sessionId, userId, refreshHash, Timestamp.from(expiresAt.toInstant()), now, now);
    }

    @Transactional
    public Optional<ConsolePrincipal> rotateSession(String sessionId, String previousHash, String nextHash, OffsetDateTime expiresAt) {
        int changed = jdbc.update("""
                UPDATE auth_session SET refresh_token_hash = ?, expires_at = ?, updated_at = NOW()
                WHERE id = ? AND refresh_token_hash = ? AND revoked_at IS NULL AND expires_at > NOW()
                """, nextHash, Timestamp.from(expiresAt.toInstant()), sessionId, previousHash);
        if (changed == 0) return Optional.empty();
        return principalBySession(sessionId);
    }

    @Transactional
    public void revokeSession(String sessionId) { jdbc.update("UPDATE auth_session SET revoked_at = NOW(), updated_at = NOW() WHERE id = ?", sessionId); }

    @Transactional
    public void updatePasswordAndRevokeSessions(long userId, String passwordHash) {
        jdbc.update("UPDATE platform_user SET password_hash = ?, password_change_required = 0, updated_at = NOW() WHERE id = ?", passwordHash, userId);
        jdbc.update("UPDATE auth_session SET revoked_at = NOW(), updated_at = NOW() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    @Transactional
    public void applyBootstrapPasswordHash(String email, String passwordHash) {
        jdbc.update("""
                UPDATE platform_user SET password_hash = ?, password_change_required = 1, platform_admin = 1,
                updated_at = NOW() WHERE email = ? AND password_hash IS NULL
                """, passwordHash, email.trim().toLowerCase());
    }

    /** Replaces all console credentials with deterministic local-development credentials. */
    @Transactional
    public void resetDevelopmentCredentials(String passwordHash) {
        List<DevelopmentUser> users = jdbc.query("SELECT id, name, platform_admin FROM platform_user ORDER BY id",
                (rs, row) -> new DevelopmentUser(rs.getLong("id"), rs.getString("name"), rs.getInt("platform_admin") == 1));
        Set<String> assigned = new HashSet<>();
        boolean administratorAssigned = false;
        List<DevelopmentAccount> accounts = new java.util.ArrayList<>();
        for (DevelopmentUser user : users) {
            String email;
            if (user.platformAdmin() && !administratorAssigned) {
                email = "admin";
                administratorAssigned = true;
            } else {
                email = nextDevelopmentEmail(user.name(), assigned);
            }
            assigned.add(email);
            accounts.add(new DevelopmentAccount(user.id(), email));
        }

        // Move every row out of the final namespace before assigning generated unique addresses.
        String temporaryPrefix = "development-reset-" + UUID.randomUUID() + "-";
        jdbc.update("UPDATE platform_user SET email = CONCAT(?, id, '@invalid.local'), updated_at = NOW()", temporaryPrefix);
        for (DevelopmentAccount account : accounts) {
            jdbc.update("UPDATE platform_user SET email = ?, password_hash = ?, password_change_required = 0, updated_at = NOW() WHERE id = ?",
                    account.email(), passwordHash, account.userId());
        }
        jdbc.update("UPDATE team_member m JOIN platform_user u ON u.id = m.user_id SET m.email = u.email");
        jdbc.update("UPDATE auth_session SET revoked_at = NOW(), updated_at = NOW() WHERE revoked_at IS NULL");
    }

    public String nextDevelopmentEmail(String name) {
        Set<String> existing = new HashSet<>(jdbc.queryForList("SELECT email FROM platform_user", String.class));
        return nextDevelopmentEmail(name, existing);
    }

    private static String nextDevelopmentEmail(String name, Set<String> assigned) {
        int sequence = 1;
        String candidate;
        do {
            candidate = DevelopmentAccountNames.emailFor(name, sequence++);
        } while (assigned.contains(candidate));
        return candidate;
    }

    private Optional<ConsolePrincipal> principalBySession(String sessionId) {
        Long userId;
        try { userId = jdbc.queryForObject("SELECT user_id FROM auth_session WHERE id = ?", Long.class, sessionId); }
        catch (EmptyResultDataAccessException ex) { return Optional.empty(); }
        return findActivePrincipal(userId, sessionId);
    }

    private static Long nullableLong(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private record DevelopmentUser(long id, String name, boolean platformAdmin) { }
    private record DevelopmentAccount(long userId, String email) { }
    public record Account(long userId, String name, String email, String passwordHash, boolean passwordChangeRequired, boolean enabled) { }
}
