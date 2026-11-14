package com.ticketflow.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIdempotencyStoreTest {

    private static final Duration TTL = Duration.ofMinutes(1);

    @Test
    void firstClaimWinsAndLaterClaimsSeeTheRecord() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

        assertThat(store.claim("k", "fp", TTL)).isEmpty();
        assertThat(store.claim("k", "fp", TTL))
                .hasValueSatisfying(r -> assertThat(r.state()).isEqualTo(IdempotencyRecord.State.IN_PROGRESS));
    }

    @Test
    void completedRecordIsReturnedForReplay() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.claim("k", "fp", TTL);
        store.complete("k", IdempotencyRecord.completed("fp", 200, "application/json", "{}"), TTL);

        assertThat(store.claim("k", "fp", TTL)).hasValueSatisfying(r -> {
            assertThat(r.state()).isEqualTo(IdempotencyRecord.State.COMPLETED);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.body()).isEqualTo("{}");
        });
    }

    @Test
    void releasedKeyCanBeClaimedAgain() {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        store.claim("k", "fp", TTL);
        store.release("k");

        assertThat(store.claim("k", "fp", TTL)).isEmpty();
    }

    @Test
    void expiredKeyCanBeClaimedAgain() {
        MutableClock clock = new MutableClock();
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(clock);
        store.claim("k", "fp", TTL);

        clock.advance(TTL.plusSeconds(1));

        assertThat(store.claim("k", "fp", TTL)).isEmpty();
    }

    @Test
    void exactlyOneConcurrentClaimWins() throws Exception {
        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
        List<Callable<Boolean>> claims = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            claims.add(() -> store.claim("hot-key", "fp", TTL).isEmpty());
        }

        int winners = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (Future<Boolean> f : pool.invokeAll(claims)) {
                if (f.get()) {
                    winners++;
                }
            }
        }
        assertThat(winners).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
