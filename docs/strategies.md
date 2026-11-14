# Inventory strategies, in depth

Every strategy answers the same question: *a buyer wants `n` tickets from tier `t`; can they have them?* The answer must stay correct when thousands of buyers ask at the same moment.

Each one lives in [`backend/src/main/java/com/ticketflow/inventory/strategy/`](../backend/src/main/java/com/ticketflow/inventory/strategy) and implements:

```java
public interface InventoryStrategy {
    InventoryStrategyType type();
    DeductResult tryDeduct(long tierId, int quantity);   // SUCCESS | SOLD_OUT | CONFLICT
    void release(long tierId, int quantity);             // expiry, cancel, compensation
    default boolean isAvailable() { return true; }      // e.g. is Redis reachable?
    default void warmUp(long tierId) {}
    default void evict(long tierId) {}
}
```

Each strategy owns its transaction boundary. Checkout calls `tryDeduct` first and only then saves the order; if saving fails it calls `release` (compensation). The Lab calls `tryDeduct` directly from many threads.

---

## NAIVE: read, decide, write

```sql
SELECT available_capacity FROM ticket_tiers WHERE id = :id;   -- returns 10
-- Java: 10 >= 1, so compute 10 - 1 = 9
UPDATE ticket_tiers SET available_capacity = 9 WHERE id = :id;
```

**Why it breaks:** two buyers both read `10` and both write `9`. Both were told "you got a ticket", but stock only went down by one. This is a *lost update*. With 1,000 buyers on 100 tickets, every buyer can read a positive number before anyone's write lands, so nearly all of them "succeed".

**Why it is hard to notice:** with one buyer per ticket (500/500), NAIVE sold exactly 500 in our runs, so a check of "how many sold" passes. But the database ended with **475 seats still marked free**, and those would be sold again to the next wave of buyers. The Lab reports these as **phantom stock**.

The strategy sleeps 2 ms between read and write (`ticketflow.inventory.naive-think-time-ms`) to stand in for the pricing, fraud and logging calls real code makes there. Without it the race still exists; it just needs more traffic to show up.

**Where you see this in the wild:** `findById` → `if (stock >= n)` → `setStock(stock - n)` → `save()` on an entity *without* a `@Version` column, or any cache-aside counter updated with `GET` then `SET`.

---

## PESSIMISTIC_LOCK: take a lock, then decide

```sql
BEGIN;
SELECT * FROM ticket_tiers WHERE id = :id FOR UPDATE;  -- other buyers block here
UPDATE ticket_tiers SET available_capacity = available_capacity - 1, version = version + 1 WHERE id = :id;
COMMIT;                                                 -- next buyer proceeds
```

**Correct:** only one transaction at a time can hold the row lock, so the read-check-write is serialised.

**Cost:** throughput is roughly `1 / (lock hold time)`. Every waiting buyer also holds a pooled DB connection while it waits, so a hot tier can starve unrelated queries of connections. Watch `hikaricp_connections_pending` in the Grafana dashboard during a run.

**Pick it when:** the decision really needs several rows or application logic under one lock (e.g. "allocate seats next to each other"). Keep the locked section tiny.

---

## OPTIMISTIC_LOCK: decide, then check nobody else did

```sql
SELECT available_capacity, version FROM ticket_tiers WHERE id = :id;  -- 10, v7
UPDATE ticket_tiers SET available_capacity = 9, version = 8
WHERE id = :id AND version = 7;                                       -- 0 rows if someone beat us
```

JPA does this automatically for entities with `@Version`. On a conflict we retry in a fresh transaction, up to `ticketflow.inventory.optimistic-max-retries` (default 3) times.

**Correct:** a write based on a stale read always fails.

**Cost:** on a hot row almost every attempt conflicts. In our 500 buyers / 500 seats run only about 193 buyers succeeded and **307 seats were left unsold**, because buyers ran out of retries while stock remained. More retries trade unsold seats for latency and DB load.

**Pick it when:** conflicts are rare (editing a profile, updating an order) and not for one counter that everyone hits at once.

---

## ATOMIC_UPDATE: let the database decide (default)

```sql
UPDATE ticket_tiers
SET available_capacity = available_capacity - :n
WHERE id = :id AND available_capacity >= :n;
-- 1 row updated -> reserved, 0 rows -> sold out
```

**Correct:** the condition is evaluated under the row lock that the `UPDATE` takes anyway. There is no read-then-write gap and nothing to retry.

**Fast:** the lock is held for one statement, not for a network round trip to Java and back. In our flash-sale run it did ~10.5k reservations/s against ~2k for the explicit lock.

**Limit:** all buyers still converge on one row in one database. At very high traffic you want to stop most of them before they reach the database, which is what the next strategy does.

---

## REDIS_LUA: gatekeeper in front of the database

```lua
-- KEYS[1] = ticketflow:stock:{tierId}, ARGV[1] = quantity
local stock = redis.call('GET', KEYS[1])
if not stock then return -1 end              -- not loaded yet
if tonumber(stock) < tonumber(ARGV[1]) then return 0 end
redis.call('DECRBY', KEYS[1], ARGV[1])
return 1
```

1. Redis runs the script atomically (it is single-threaded), so the check-and-decrement cannot interleave.
2. Only buyers who won in Redis run the `ATOMIC_UPDATE` statement. The database stays the source of truth.
3. If the database says no (Redis drifted high), the Redis decrement is undone and the buyer gets "sold out".

**Why it matters:** once stock reaches zero, the remaining buyers of a flash sale get their "sold out" from Redis in microseconds and never touch PostgreSQL.

**Failure modes and how they are handled:**

| What happens | Effect | Why it's safe |
|---|---|---|
| Redis value higher than DB | Extra buyers pass the gate | DB `WHERE stock >= n` rejects them; Redis is compensated |
| Redis value lower than DB | Some buyers wrongly told "sold out" | Never oversells; expires/cancels add back with `INCRBY` |
| Key missing (restart, eviction) | Script returns -1 | Reloaded from DB with `SET NX`, so concurrent loaders don't clobber each other |
| Redis down | Strategy reports unavailable | Checkout keeps using whatever you configured; the Lab disables it |

**Pick it when:** demand is far above supply and the database is the bottleneck. You pay for it with another moving part.

---

## Adding your own

1. Create `inventory/strategy/YourStrategy.java` implementing `InventoryStrategy` as a Spring `@Component`.
2. Add a value to `InventoryStrategyType` with a display name, description and whether it is `safe`.
3. Run `./mvnw verify`: the Lab, the strategy tests and the UI pick it up automatically.

Strategies we'd love to see: a single-writer queue per tier, sharded counters, PostgreSQL advisory locks, and `FOR UPDATE SKIP LOCKED` for assigned seating.
