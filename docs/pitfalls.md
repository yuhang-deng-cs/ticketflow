# Bugs we found (and fixed)

TicketFlow's first version claimed "zero oversell", and the headline concurrency test passed. A review still found the bugs below, each of which would have shown up in production. They are written down because they are the interesting part.

## 1. Payment racing the expiry sweeper sold the same seat twice

**Before:**

```java
// payOrder
Order order = repo.findByOrderNumber(n);
if (order.getStatus() != PENDING) throw ...;
if (now.isAfter(order.getExpiresAt())) { release(order); order.setStatus(EXPIRED); ... }
order.setStatus(CONFIRMED);
repo.save(order);

// @Scheduled sweeper, every 30 s
for (Order o : repo.findExpiredOrders(PENDING, now)) {
    release(o);              // stock goes back on sale
    o.setStatus(EXPIRED);
    repo.save(o);
}
```

At the moment a hold runs out, both can read `PENDING`. The sweeper releases the seat and writes `EXPIRED`; the payment, still holding its stale copy, writes `CONFIRMED` (the `Order` entity had no `@Version`, so nothing stopped it). Result: a paid order **and** the same seat back on sale.

Two more problems sat in the same code:

- The "expired" branch in `payOrder` released stock and then threw an exception inside the same `@Transactional` method. That rolled back the expiry it had just written, so the order stayed `PENDING`.
- Running two instances meant two sweepers releasing the same orders: double release, more phantom stock.

**After:** every state change is a compare-and-set, and only the caller whose `UPDATE` matched releases stock.

```sql
UPDATE orders SET status = 'CONFIRMED' WHERE id = :id AND status = 'PENDING' AND expires_at >  :now;
UPDATE orders SET status = 'EXPIRED'   WHERE id = :id AND status = 'PENDING' AND expires_at <= :now;
```

Exactly one can match. The same pattern makes the sweeper safe across instances with no distributed lock. Regression test: `BookingServiceTest.paymentAndExpiryNeverBothWin`, which fires a payment and an expiry at 30 orders at their deadline and checks that `available stock == number of expired orders`.

## 2. "Idempotency" that rejected retries instead of answering them

**Before:** a filter did `SETNX idemp:{key}` for 2 minutes and answered any repeat with `409 Duplicate request`.

- A client whose first request **succeeded** but whose response was lost (the reason idempotency exists) retried and got a 409, not its order. From the client's side the booking had failed.
- The key was global, so two users sending the same key collided.
- The key was never released, so after a 500 the client could not retry for 2 minutes.
- The frontend used `pay-{orderNumber}` as the payment key, so after one failed payment attempt the user was locked out of paying for 2 minutes.
- `Idempotency-Key` was missing from the CORS allowed headers, so in local dev (5173 → 8080) every booking failed its preflight. Docker hid this because nginx made the calls same-origin.

**After** ([`IdempotencyFilter`](../backend/src/main/java/com/ticketflow/idempotency/IdempotencyFilter.java)):

| Situation | Response |
|---|---|
| First request | Processed; final response stored 24 h |
| Same key, first still running | `409` |
| Same key, first finished | **The stored response is replayed**, with `Idempotent-Replayed: true` |
| Same key, different body or path | `422` |
| First request returned 5xx | Key released so the client can retry |

Keys are scoped per user, the frontend generates one UUID per user action, and the dev setup proxies `/api` through Vite so it behaves like production.

## 3. The concurrency test ran on a different database than production

The "50 threads, 10 tickets" test ran on H2. H2's locking is not PostgreSQL's, so a green test said little about production. The strategy tests now also run on PostgreSQL 16 and Redis 7 through Testcontainers in CI, with Flyway migrations and `ddl-auto=validate`, the same as production.

## 4. Smaller things

- **Hard-coded JWT secret** in `application.yml`, used in every environment. The prod profile now has no default and refuses to start without `JWT_SECRET`; secrets are injected from SSM in the Terraform setup.
- **All of `/actuator/**` and the H2 console were public** in every profile. Now only health/info/prometheus are public, the rest needs ADMIN, and the H2 console only exists in dev.
- **500 responses echoed `ex.getMessage()`** to clients, leaking internals. They now return a generic message plus the `traceId`, and the details go to the log.
- **Client-supplied `X-Trace-Id` went straight into logs.** Now validated (`[A-Za-z0-9-]{8,64}`) to prevent log injection.
- **Another user's order returned 403**, which confirmed that the order number exists. Now 404.
- **Registration checked email uniqueness before lower-casing it**, so `Demo@x.com` and `demo@x.com` could both register.
- **`backend/mvnw` was committed without the executable bit**, so the Docker build's `RUN ./mvnw` failed on Linux.
- **Terraform**: the RDS password had a default value; there was no S3 bucket policy for CloudFront's origin access, so the CDN could not read the site; and the backend image pointed at a registry path that did not exist.
- **Marketing numbers in the UI** ("p95 < 45 ms", "System Healthy") were hard-coded. The banner now shows live status from the API, and the README numbers come from reproducible runs.
