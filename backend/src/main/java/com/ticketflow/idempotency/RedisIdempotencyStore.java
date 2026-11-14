package com.ticketflow.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Shared store for multi-instance deployments. {@code SET NX PX} makes the claim atomic across nodes.
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String PREFIX = "ticketflow:idem:";
    private static final int CLAIM_ATTEMPTS = 2;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IdempotencyRecord> claim(String key, String fingerprint, Duration lockTtl) {
        String redisKey = PREFIX + key;
        String inProgress = write(IdempotencyRecord.inProgress(fingerprint));
        for (int attempt = 0; attempt < CLAIM_ATTEMPTS; attempt++) {
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, inProgress, lockTtl))) {
                return Optional.empty();
            }
            String existing = redis.opsForValue().get(redisKey);
            if (existing != null) {
                return Optional.of(read(existing));
            }
            // The key expired between SET NX and GET; try to claim it again.
        }
        throw new IllegalStateException("Could not claim idempotency key");
    }

    @Override
    public void complete(String key, IdempotencyRecord record, Duration retention) {
        redis.opsForValue().set(PREFIX + key, write(record), retention);
    }

    @Override
    public void release(String key) {
        redis.delete(PREFIX + key);
    }

    private String write(IdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise idempotency record", e);
        }
    }

    private IdempotencyRecord read(String json) {
        try {
            return objectMapper.readValue(json, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt idempotency record", e);
        }
    }
}
