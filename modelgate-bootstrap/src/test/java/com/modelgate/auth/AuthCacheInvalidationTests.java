package com.modelgate.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelgate.infrastructure.db.AdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCacheInvalidationTests {
    private static final String KEY_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void keyHashEventIsVersionedAndRejectsPlaintext() throws Exception {
        AuthCacheInvalidationEvent event = AuthCacheInvalidationEvent.keyHashes(List.of(KEY_HASH));
        String json = new ObjectMapper().writeValueAsString(event);

        assertThat(json).contains("\"version\":1").contains(KEY_HASH).doesNotContain("mg-key-");
        assertThat(new ObjectMapper().readValue(json, AuthCacheInvalidationEvent.class)).isEqualTo(event);
        assertThatThrownBy(() -> AuthCacheInvalidationEvent.keyHashes(List.of("mg-key-example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHA-256");
    }

    @Test
    void subscriberEvictsOnlyLocalCacheWithoutRepublishing() {
        RecordingHandler handler = new RecordingHandler();
        AuthCacheInvalidationSubscriber subscriber = new AuthCacheInvalidationSubscriber(new ObjectMapper(), handler);

        subscriber.handle(AuthCacheInvalidationEvent.keyHashes(List.of(KEY_HASH)));
        subscriber.handle(AuthCacheInvalidationEvent.member(42L));
        subscriber.handle(AuthCacheInvalidationEvent.team(7L));

        assertThat(handler.keyHashInvalidations).containsExactly(List.of(KEY_HASH));
        assertThat(handler.memberInvalidations).containsExactly(42L);
        assertThat(handler.teamInvalidations).containsExactly(7L);
    }

    @Test
    void invalidationPublishesOnlyAfterTransactionCommit() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RecordingPublisher publisher = new RecordingPublisher();
        VirtualKeyService keys = new VirtualKeyService(new AdminRepository(new JdbcTemplate()), redis, new ObjectMapper(), publisher);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            keys.invalidateKeyHashes(List.of(KEY_HASH));
            assertThat(redis.deletedKeys).isEmpty();
            assertThat(publisher.events).isEmpty();

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            assertThat(redis.deletedKeys).containsExactly("auth:key:" + KEY_HASH);
            assertThat(publisher.events).singleElement().satisfies(event -> {
                assertThat(event.targetType()).isEqualTo(AuthCacheInvalidationEvent.TargetType.KEY_HASHES);
                assertThat(event.keyHashes()).containsExactly(KEY_HASH);
            });
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void rollbackDoesNotPublishOrDeleteDistributedCache() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RecordingPublisher publisher = new RecordingPublisher();
        VirtualKeyService keys = new VirtualKeyService(new AdminRepository(new JdbcTemplate()), redis, new ObjectMapper(), publisher);

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            keys.invalidateKeyHashes(List.of(KEY_HASH));
            assertThat(redis.deletedKeys).isEmpty();
            assertThat(publisher.events).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void emptyHashSetKeepsExistingNoOpSemantics() {
        RecordingRedisTemplate redis = new RecordingRedisTemplate();
        RecordingPublisher publisher = new RecordingPublisher();
        VirtualKeyService keys = new VirtualKeyService(new AdminRepository(new JdbcTemplate()), redis, new ObjectMapper(), publisher);

        keys.invalidateKeyHashes(List.of());

        assertThat(redis.deletedKeys).isEmpty();
        assertThat(publisher.events).isEmpty();
    }

    private static final class RecordingHandler implements AuthCacheInvalidationHandler {
        private final List<List<String>> keyHashInvalidations = new ArrayList<>();
        private final List<Long> memberInvalidations = new ArrayList<>();
        private final List<Long> teamInvalidations = new ArrayList<>();

        @Override
        public void evictLocalKeyHashes(Iterable<String> hashes) {
            List<String> copy = new ArrayList<>();
            hashes.forEach(copy::add);
            keyHashInvalidations.add(copy);
        }

        @Override
        public void evictLocalMember(long memberId) {
            memberInvalidations.add(memberId);
        }

        @Override
        public void evictLocalTeam(long teamId) {
            teamInvalidations.add(teamId);
        }
    }

    private static final class RecordingRedisTemplate extends StringRedisTemplate {
        private final List<String> deletedKeys = new ArrayList<>();

        @Override
        public Boolean delete(String key) {
            deletedKeys.add(key);
            return true;
        }
    }

    private static final class RecordingPublisher implements AuthCacheInvalidationPublisher {
        private final List<AuthCacheInvalidationEvent> events = new ArrayList<>();

        @Override
        public void publish(AuthCacheInvalidationEvent event) {
            events.add(event);
        }
    }
}
