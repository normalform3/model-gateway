package com.modelgate.bootstrap;

import com.modelgate.auth.ConsoleAuthService;
import com.modelgate.common.auth.ConsolePrincipal;
import com.modelgate.common.error.ErrorCode;
import com.modelgate.common.error.ModelGateException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class ConsoleSecurityConfiguration {
    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            @Qualifier("consoleTokenAuthenticationFilter") WebFilter consoleTokenAuthenticationFilter,
            @Qualifier("consoleRoleAuthorizationFilter") WebFilter consoleRoleAuthorizationFilter
    ) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable).formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges.pathMatchers("/auth/login", "/auth/refresh", "/auth/development-credentials", "/v1/**", "/actuator/health").permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((exchange, ex) -> writeError(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED))
                        .accessDeniedHandler((exchange, ex) -> writeError(exchange, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED)))
                .addFilterAt(consoleTokenAuthenticationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(consoleRoleAuthorizationFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    WebFilter consoleTokenAuthenticationFilter(ConsoleAuthService authService) {
        return (exchange, chain) -> {
            String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith("Bearer ")) return chain.filter(exchange);
            return Mono.fromCallable(() -> authService.authenticateAccessToken(header.substring(7)))
                    .subscribeOn(Schedulers.boundedElastic()).flatMap(principal -> {
                        var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                        return chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
                    }).onErrorResume(ModelGateException.class, ex -> writeError(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED));
        };
    }

    @Bean
    WebFilter consoleRoleAuthorizationFilter() {
        return (exchange, chain) -> ReactiveSecurityContextHolder.getContext()
                .map(context -> authorize(exchange, chain, context.getAuthentication().getPrincipal()))
                .switchIfEmpty(Mono.defer(() -> Mono.just(chain.filter(exchange))))
                .flatMap(inner -> inner);
    }

    private static Mono<Void> authorize(org.springframework.web.server.ServerWebExchange exchange,
                                        org.springframework.web.server.WebFilterChain chain,
                                        Object authenticationPrincipal) {
        if (!(authenticationPrincipal instanceof ConsolePrincipal principal)) return chain.filter(exchange);
        String path = exchange.getRequest().getPath().value();
        if (principal.passwordChangeRequired() && !(path.equals("/auth/me") || path.equals("/auth/me/password") || path.equals("/auth/logout"))) {
            return writeError(exchange, HttpStatus.FORBIDDEN, ErrorCode.PASSWORD_CHANGE_REQUIRED);
        }
        if (!path.startsWith("/admin")) return chain.filter(exchange);
        if (principal.isPlatformAdmin()) return chain.filter(exchange);
        if (principal.isTeamAdmin() && (ownsTeamPath(path, principal.teamId())
                || ownsMemberPath(path, principal.memberId())
                || isOwnTeamDirectory(exchange, principal)
                || isModelCatalog(exchange))) return chain.filter(exchange);
        if (principal.isDeveloper() && ownsMemberPath(path, principal.memberId())) return chain.filter(exchange);
        return writeError(exchange, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED);
    }

    private static boolean ownsTeamPath(String path, Long teamId) {
        return teamId != null && (path.startsWith("/admin/teams/" + teamId + "/") || path.equals("/admin/teams/" + teamId));
    }
    private static boolean ownsMemberPath(String path, Long memberId) {
        return memberId != null && path.startsWith("/admin/members/" + memberId + "/");
    }
    private static boolean isOwnTeamDirectory(org.springframework.web.server.ServerWebExchange exchange, ConsolePrincipal principal) {
        String ownerUserId = exchange.getRequest().getQueryParams().getFirst("ownerUserId");
        return exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.GET && "/admin/teams".equals(exchange.getRequest().getPath().value())
                && Long.toString(principal.userId()).equals(ownerUserId);
    }
    private static boolean isModelCatalog(org.springframework.web.server.ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.GET && "/admin/models".equals(exchange.getRequest().getPath().value());
    }
    private static Mono<Void> writeError(org.springframework.web.server.ServerWebExchange exchange, HttpStatus status, ErrorCode code) {
        exchange.getResponse().setStatusCode(status); exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"error\":{\"code\":\"" + code.name() + "\",\"message\":\"" + code.defaultMessage() + "\",\"requestId\":null,\"retryable\":false}}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
