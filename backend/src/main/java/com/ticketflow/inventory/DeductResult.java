package com.ticketflow.inventory;

public enum DeductResult {
    /** Stock was reserved. */
    SUCCESS,
    /** Not enough stock left. */
    SOLD_OUT,
    /** Gave up because of contention (e.g. optimistic-lock retries exhausted) although stock may remain. */
    CONFLICT
}
