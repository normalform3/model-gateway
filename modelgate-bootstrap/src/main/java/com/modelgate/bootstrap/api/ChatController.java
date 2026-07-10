package com.modelgate.bootstrap.api;

import com.modelgate.common.chat.ChatCompletionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
public class ChatController {
    private final ChatGatewayService chatGatewayService;

    public ChatController(ChatGatewayService chatGatewayService) {
        this.chatGatewayService = chatGatewayService;
    }

    @PostMapping(path = "/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> completions(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ChatCompletionRequest request
    ) {
        if (request.streamEnabled()) {
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(chatGatewayService.stream(authorization, idempotencyKey, request)));
        }
        return chatGatewayService.complete(authorization, idempotencyKey, request)
                .map(response -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response));
    }
}
