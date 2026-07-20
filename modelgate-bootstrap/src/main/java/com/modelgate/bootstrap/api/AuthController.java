package com.modelgate.bootstrap.api;

import com.modelgate.auth.ConsoleAuthProperties;
import com.modelgate.auth.ConsoleAuthService;
import com.modelgate.common.auth.ConsolePrincipal;
import com.modelgate.common.api.AuthDtos.ChangePasswordRequest;
import com.modelgate.common.api.AuthDtos.ConsoleIdentity;
import com.modelgate.common.api.AuthDtos.DevelopmentCredentialsStatus;
import com.modelgate.common.api.AuthDtos.LoginRequest;
import com.modelgate.common.api.AuthDtos.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping(path = "/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {
    private static final String REFRESH_COOKIE = "mg_console_refresh";
    private final ConsoleAuthService authService;
    private final ConsoleAuthProperties properties;

    public AuthController(ConsoleAuthService authService, ConsoleAuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/login")
    public Mono<ResponseEntity<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        return Mono.fromCallable(() -> authService.login(request.email(), request.password()))
                .subscribeOn(Schedulers.boundedElastic()).map(login -> withRefreshCookie(login.response(), login.refreshToken()));
    }

    @GetMapping("/development-credentials")
    public Mono<DevelopmentCredentialsStatus> developmentCredentials() {
        return Mono.just(new DevelopmentCredentialsStatus(authService.developmentDefaultCredentialsEnabled()));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<LoginResponse>> refresh(@org.springframework.web.bind.annotation.CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return Mono.fromCallable(() -> authService.refresh(refreshToken)).subscribeOn(Schedulers.boundedElastic())
                .map(login -> withRefreshCookie(login.response(), login.refreshToken()));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@AuthenticationPrincipal ConsolePrincipal principal) {
        return Mono.fromRunnable(() -> authService.logout(principal)).subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredCookie().toString()).build());
    }

    @GetMapping("/me")
    public Mono<ConsoleIdentity> me(@AuthenticationPrincipal ConsolePrincipal principal) { return Mono.just(principal.identity()); }

    @PutMapping("/me/password")
    public Mono<ResponseEntity<Void>> changePassword(@AuthenticationPrincipal ConsolePrincipal principal,
                                                     @Valid @RequestBody ChangePasswordRequest request) {
        return Mono.fromRunnable(() -> authService.changePassword(principal, request.currentPassword(), request.newPassword()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expiredCookie().toString()).build());
    }

    private ResponseEntity<LoginResponse> withRefreshCookie(LoginResponse response, String refreshToken) {
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(refreshToken, ConsoleAuthService.REFRESH_TOKEN_TTL.toSeconds()).toString()).body(response);
    }
    private ResponseCookie expiredCookie() { return cookie("", 0); }
    private ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value).httpOnly(true).secure(properties.isRefreshCookieSecure())
                .sameSite("Strict").path("/auth").maxAge(maxAge).build();
    }
}
