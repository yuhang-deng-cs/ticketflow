package com.ticketflow.inventory;

/**
 * A way of reserving stock for a ticket tier under concurrent load.
 *
 * <p>Every implementation owns its own transaction boundary, so callers can use it
 * directly (the Oversell Lab does) or compose it with other work and compensate via
 * {@link #release(long, int)} if that later work fails (the booking flow does).
 */
public interface InventoryStrategy {

    InventoryStrategyType type();

    /**
     * Attempts to take {@code quantity} units from the tier's available capacity.
     */
    DeductResult tryDeduct(long tierId, int quantity);

    /**
     * Returns {@code quantity} units to the tier, e.g. when a hold expires or a later step fails.
     */
    void release(long tierId, int quantity);

    /**
     * Whether the infrastructure this strategy needs (e.g. Redis) is reachable right now.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Prepares any external state for the tier, e.g. loads stock into a cache. Default: nothing.
     */
    default void warmUp(long tierId) {
    }

    /**
     * Drops any external state held for the tier. Default: nothing.
     */
    default void evict(long tierId) {
    }
}
