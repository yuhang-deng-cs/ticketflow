package com.ticketflow.inventory.strategy;

import com.ticketflow.event.repository.TicketTierRepository;
import com.ticketflow.inventory.DeductResult;
import com.ticketflow.inventory.InventoryStrategy;
import com.ticketflow.inventory.InventoryStrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Redis as a gate in front of the database.
 *
 * <ol>
 *   <li>A Lua script checks and decrements the cached stock atomically (Redis runs scripts single-threaded).</li>
 *   <li>Only winners go on to the conditional atomic UPDATE in the database, which stays the source of truth.</li>
 *   <li>If the database disagrees (cache drift), the Redis decrement is compensated and the buyer is rejected.</li>
 * </ol>
 *
 * <p>Once stock hits zero, every further request is answered by Redis alone, which is what keeps the database
 * alive during a flash sale. Drift can only cause false rejections, never overselling, because the database
 * still enforces {@code stock >= n}.
 */
@Slf4j
@Component
public class RedisLuaInventoryStrategy implements InventoryStrategy {

    static final String KEY_PREFIX = "ticketflow:stock:";

    private static final RedisScript<Long> DEDUCT_SCRIPT = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if not stock then return -1 end
            local qty = tonumber(ARGV[1])
            if tonumber(stock) < qty then return 0 end
            redis.call('DECRBY', KEYS[1], qty)
            return 1
            """, Long.class);

    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return -1
            """, Long.class);

    private static final Duration AVAILABILITY_CACHE = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final TicketTierRepository repository;
    private final TransactionTemplate transactionTemplate;

    private volatile boolean lastAvailability;
    private volatile long availabilityCheckedAt;

    public RedisLuaInventoryStrategy(StringRedisTemplate redis,
                                     TicketTierRepository repository,
                                     TransactionTemplate transactionTemplate) {
        this.redis = redis;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public InventoryStrategyType type() {
        return InventoryStrategyType.REDIS_LUA;
    }

    @Override
    public DeductResult tryDeduct(long tierId, int quantity) {
        long gate = runDeductScript(tierId, quantity);
        if (gate == -1) {
            loadStockIfAbsent(tierId);
            gate = runDeductScript(tierId, quantity);
        }
        if (gate != 1) {
            return DeductResult.SOLD_OUT;
        }

        Integer updated;
        try {
            updated = transactionTemplate.execute(status -> repository.deductCapacityAtomically(tierId, quantity));
        } catch (RuntimeException e) {
            compensate(tierId, quantity);
            throw e;
        }
        if (updated == null || updated == 0) {
            log.warn("Redis stock for tier {} drifted above the database; compensating", tierId);
            compensate(tierId, quantity);
            return DeductResult.SOLD_OUT;
        }
        return DeductResult.SUCCESS;
    }

    @Override
    public void release(long tierId, int quantity) {
        transactionTemplate.executeWithoutResult(status -> repository.releaseCapacity(tierId, quantity));
        compensate(tierId, quantity);
    }

    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - availabilityCheckedAt < AVAILABILITY_CACHE.toMillis()) {
            return lastAvailability;
        }
        boolean available;
        try {
            available = "PONG".equalsIgnoreCase(redis.execute((RedisCallback<String>) RedisConnection::ping));
        } catch (RuntimeException e) {
            available = false;
        }
        lastAvailability = available;
        availabilityCheckedAt = now;
        return available;
    }

    @Override
    public void warmUp(long tierId) {
        redis.opsForValue().set(key(tierId), String.valueOf(currentDatabaseStock(tierId)));
    }

    @Override
    public void evict(long tierId) {
        redis.delete(key(tierId));
    }

    private long runDeductScript(long tierId, int quantity) {
        Long result = redis.execute(DEDUCT_SCRIPT, List.of(key(tierId)), String.valueOf(quantity));
        return result == null ? 0 : result;
    }

    private void loadStockIfAbsent(long tierId) {
        redis.opsForValue().setIfAbsent(key(tierId), String.valueOf(currentDatabaseStock(tierId)));
    }

    private int currentDatabaseStock(long tierId) {
        return repository.findAvailableCapacity(tierId)
                .orElseThrow(() -> new NoSuchElementException("Ticket tier not found: " + tierId));
    }

    private void compensate(long tierId, int quantity) {
        redis.execute(RELEASE_SCRIPT, List.of(key(tierId)), String.valueOf(quantity));
    }

    private static String key(long tierId) {
        return KEY_PREFIX + tierId;
    }
}
