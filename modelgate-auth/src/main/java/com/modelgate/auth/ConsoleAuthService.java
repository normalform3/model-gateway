package com.modelgate.auth;

import com.modelgate.common.api.AuthDtos.LoginResponse;
import com.modelgate.common.auth.ConsolePrincipal;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import com.modelgate.infrastructure.db.ConsoleAuthRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.security.SecureRandom;

/** Authentication lifecycle for browser control-plane users. Gateway virtual keys never use this service. */
@Service
public class ConsoleAuthService {
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    private static final String DEVELOPMENT_DEFAULT_PASSWORD = "123";
    private final ConsoleAuthRepository repository;
    private final ConsoleAuthProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final Map<String, FailedLogin> failedLogins = new ConcurrentHashMap<>();

    public ConsoleAuthService(ConsoleAuthRepository repository, ConsoleAuthProperties properties, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder, JwtDecoder jwtDecoder) {
        this.repository = repository;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
    }

    public SessionLogin login(String rawEmail, String password) {
        String email = rawEmail.trim().toLowerCase();
        ensureNotRateLimited(email);
        ConsoleAuthRepository.Account account = repository.findEnabledAccountByEmail(email).orElse(null);
        if (account == null || account.passwordHash() == null || !passwordEncoder.matches(password, account.passwordHash())) {
            recordFailure(email);
            throw new ModelGateException(ErrorCode.AUTHENTICATION_FAILED);
        }
        clearFailures(email);
        String sessionId = UUID.randomUUID().toString();
        String refreshToken = refreshToken(sessionId);
        repository.createSession(sessionId, account.userId(), sha256(refreshToken), OffsetDateTime.now(ZoneOffset.UTC).plus(REFRESH_TOKEN_TTL));
        ConsolePrincipal principal = repository.findActivePrincipal(account.userId(), sessionId)
                .orElseThrow(() -> new ModelGateException(ErrorCode.ACCESS_DENIED, "This account has no active console role."));
        return new SessionLogin(response(principal), refreshToken);
    }

    public SessionLogin refresh(String refreshToken) {
        ParsedRefresh parsed = parseRefreshToken(refreshToken);
        String next = refreshToken(parsed.sessionId());
        ConsolePrincipal principal = repository.rotateSession(parsed.sessionId(), sha256(refreshToken), sha256(next),
                        OffsetDateTime.now(ZoneOffset.UTC).plus(REFRESH_TOKEN_TTL))
                .orElseThrow(() -> new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED));
        return new SessionLogin(response(principal), next);
    }

    public ConsolePrincipal authenticateAccessToken(String token) {
        try {
            Jwt jwt = jwtDecoder.decode(token);
            if (!"console".equals(jwt.getClaimAsString("typ"))) throw new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED);
            String sessionId = jwt.getClaimAsString("sid");
            Number userIdClaim = jwt.getClaim("uid");
            Long userId = userIdClaim == null ? null : userIdClaim.longValue();
            if (sessionId == null || userId == null) throw new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED);
            return repository.findActivePrincipal(userId, sessionId)
                    .orElseThrow(() -> new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED));
        } catch (ModelGateException ex) { throw ex; }
        catch (Exception ex) { throw new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED); }
    }

    public void logout(ConsolePrincipal principal) { repository.revokeSession(principal.sessionId()); }

    public void changePassword(ConsolePrincipal principal, String currentPassword, String newPassword) {
        ConsoleAuthRepository.Account account = repository.findEnabledAccountByEmail(principal.email())
                .orElseThrow(() -> new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED));
        if (account.passwordHash() == null || !passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new ModelGateException(ErrorCode.AUTHENTICATION_FAILED);
        }
        repository.updatePasswordAndRevokeSessions(principal.userId(), passwordEncoder.encode(newPassword));
    }

    public boolean developmentDefaultCredentialsEnabled() {
        return properties.isDevelopmentDefaultCredentialsEnabled();
    }

    public void resetDevelopmentCredentialsIfEnabled() {
        if (developmentDefaultCredentialsEnabled()) {
            repository.resetDevelopmentCredentials(passwordEncoder.encode(DEVELOPMENT_DEFAULT_PASSWORD));
        }
    }

    public String nextDevelopmentEmail(String name) {
        if (!developmentDefaultCredentialsEnabled()) throw new IllegalStateException("Development credentials are disabled.");
        return repository.nextDevelopmentEmail(name);
    }

    public String developmentDefaultPasswordHash() {
        if (!developmentDefaultCredentialsEnabled()) throw new IllegalStateException("Development credentials are disabled.");
        return passwordEncoder.encode(DEVELOPMENT_DEFAULT_PASSWORD);
    }

    private LoginResponse response(ConsolePrincipal principal) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("modelgate-console").issuedAt(now).expiresAt(now.plus(ACCESS_TOKEN_TTL))
                .subject(Long.toString(principal.userId())).claim("typ", "console").claim("sid", principal.sessionId())
                .claim("uid", principal.userId()).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", ACCESS_TOKEN_TTL.toSeconds(), principal.identity());
    }

    private void ensureNotRateLimited(String email) {
        FailedLogin failure = failedLogins.get(email);
        if (failure != null && failure.until().isAfter(Instant.now()) && failure.count() >= 5) {
            throw new ModelGateException(ErrorCode.AUTHENTICATION_FAILED, "Too many failed login attempts. Try again later.");
        }
    }
    private void recordFailure(String email) {
        failedLogins.compute(email, (key, previous) -> {
            Instant now = Instant.now();
            if (previous == null || previous.until().isBefore(now)) return new FailedLogin(1, now.plus(Duration.ofMinutes(15)));
            return new FailedLogin(previous.count() + 1, previous.until());
        });
    }
    private void clearFailures(String email) { failedLogins.remove(email); }
    private static String refreshToken(String sessionId) { return sessionId + "." + UUID.randomUUID(); }
    private static ParsedRefresh parseRefreshToken(String value) {
        String[] parts = value == null ? new String[0] : value.split("\\.");
        if (parts.length != 2) throw new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED);
        try { return new ParsedRefresh(UUID.fromString(parts[0]).toString()); }
        catch (IllegalArgumentException ex) { throw new ModelGateException(ErrorCode.AUTHENTICATION_REQUIRED); }
    }
    public static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public record SessionLogin(LoginResponse response, String refreshToken) { }
    private record ParsedRefresh(String sessionId) { }
    private record FailedLogin(int count, Instant until) { }

    @Configuration
    static class ConsoleAuthBeans {
        private static final Logger log = LoggerFactory.getLogger(ConsoleAuthBeans.class);
        @Bean PasswordEncoder consolePasswordEncoder() { return new BCryptPasswordEncoder(); }

        @Bean SecretKey consoleJwtSigningKey(ConsoleAuthProperties properties) {
            String value = properties.getJwtSecret();
            if (value != null && value.getBytes(StandardCharsets.UTF_8).length >= 32) {
                return new SecretKeySpec(value.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            log.warn("MODELGATE_CONSOLE_JWT_SECRET is not configured; using an ephemeral development signing key. Console sessions will be invalid after restart.");
            return new SecretKeySpec(generated, "HmacSHA256");
        }

        @Bean JwtEncoder consoleJwtEncoder(SecretKey consoleJwtSigningKey) {
            return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(consoleJwtSigningKey));
        }

        @Bean JwtDecoder consoleJwtDecoder(SecretKey consoleJwtSigningKey) {
            return NimbusJwtDecoder.withSecretKey(consoleJwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
        }

        @Bean ApplicationRunner bootstrapConsoleAdministrator(ConsoleAuthProperties properties, ConsoleAuthRepository repository,
                                                              ConsoleAuthService consoleAuthService) {
            return args -> {
                String hash = properties.getBootstrapAdminPasswordHash();
                if (hash != null && !hash.isBlank()) repository.applyBootstrapPasswordHash("admin", hash);
                if (properties.isInitializeDevelopmentCredentials()) {
                    if (!properties.isDevelopmentDefaultCredentialsEnabled()) {
                        throw new IllegalStateException("Development credentials must be enabled before they can be initialized.");
                    }
                    consoleAuthService.resetDevelopmentCredentialsIfEnabled();
                }
            };
        }

    }
}
