package com.ticketflow.idempotency;

import java.time.Duration;
import java.util.Optional;

public interface IdempotencyStore {

    /**
     * Atomically claims {@code key} by storing an IN_PROGRESS record, unless one already exists.
     *
     * @return empty if this caller now owns the key, otherwise the record that was already there
     */
    Optional<IdempotencyRecord> claim(String key, String fingerprint, Duration lockTtl);

    /** Replaces the IN_PROGRESS record with the final response so later retries can replay it. */
    void complete(String key, IdempotencyRecord record, Duration retention);

    /** Forgets the key so the client can retry, e.g. after a 5xx. */
    void release(String key);
}
