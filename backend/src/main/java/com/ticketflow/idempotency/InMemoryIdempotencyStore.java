package com.ticketflow.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-node store for local development and tests. Use {@link RedisIdempotencyStore} when running
 * more than one instance.
 */
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Entry(IdempotencyRecord record, long expiresAtMillis) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore() {
        this(Clock.systemUTC());
    }

    InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<IdempotencyRecord> claim(String key, String fingerprint, Duration lockTtl) {
        evictExpiredIfLarge();
        long now = clock.millis();
        AtomicReference<IdempotencyRecord> existing = new AtomicReference<>();
        entries.compute(key, (k, current) -> {
            if (current != null && current.expiresAtMillis() > now) {
                existing.set(current.record());
                return current;
            }
            return new Entry(IdempotencyRecord.inProgress(fingerprint), now + lockTtl.toMillis());
        });
        return Optional.ofNullable(existing.get());
    }

    @Override
    public void complete(String key, IdempotencyRecord record, Duration retention) {
        entries.put(key, new Entry(record, clock.millis() + retention.toMillis()));
    }

    @Override
    public void release(String key) {
        entries.remove(key);
    }

    private void evictExpiredIfLarge() {
        if (entries.size() > CLEANUP_THRESHOLD) {
            long now = clock.millis();
            entries.values().removeIf(e -> e.expiresAtMillis() <= now);
        }
    }
}
