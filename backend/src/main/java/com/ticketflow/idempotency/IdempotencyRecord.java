package com.ticketflow.idempotency;

/**
 * What the server remembers about an {@code Idempotency-Key}.
 *
 * @param state       whether the first request is still running or has finished
 * @param fingerprint hash of method, path and body, used to reject a key reused for a different request
 * @param status      HTTP status of the stored response (COMPLETED only)
 * @param contentType content type of the stored response (COMPLETED only)
 * @param body        body of the stored response (COMPLETED only)
 */
public record IdempotencyRecord(State state, String fingerprint, int status, String contentType, String body) {

    public enum State {
        IN_PROGRESS,
        COMPLETED
    }

    public static IdempotencyRecord inProgress(String fingerprint) {
        return new IdempotencyRecord(State.IN_PROGRESS, fingerprint, 0, null, null);
    }

    public static IdempotencyRecord completed(String fingerprint, int status, String contentType, String body) {
        return new IdempotencyRecord(State.COMPLETED, fingerprint, status, contentType, body);
    }
}
