package com.ticketflow.inventory;

public enum InventoryStrategyType {

    NAIVE(
            "Read-then-write",
            "SELECT the stock, check it in Java, UPDATE with the computed value. The classic lost-update bug.",
            false),
    PESSIMISTIC_LOCK(
            "Pessimistic lock",
            "SELECT ... FOR UPDATE serialises buyers on the row lock. Correct, but every buyer waits in line.",
            true),
    OPTIMISTIC_LOCK(
            "Optimistic lock + retry",
            "JPA @Version check on commit, retried a few times. Correct, but can reject buyers while stock remains.",
            true),
    ATOMIC_UPDATE(
            "Conditional atomic UPDATE",
            "UPDATE ... SET stock = stock - n WHERE stock >= n. One statement, no read, no retry.",
            true),
    REDIS_LUA(
            "Redis Lua gate + DB write",
            "A Lua script checks and decrements stock in Redis atomically, so sold-out traffic never reaches the DB.",
            true);

    private final String displayName;
    private final String description;
    private final boolean safe;

    InventoryStrategyType(String displayName, String description, boolean safe) {
        this.displayName = displayName;
        this.description = description;
        this.safe = safe;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /** Whether the strategy is expected to never oversell. */
    public boolean safe() {
        return safe;
    }
}
