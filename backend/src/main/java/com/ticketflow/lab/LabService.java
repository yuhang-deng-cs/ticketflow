package com.ticketflow.lab;

import com.ticketflow.common.BusinessException;
import com.ticketflow.event.model.Event;
import com.ticketflow.event.model.TicketTier;
import com.ticketflow.event.repository.EventRepository;
import com.ticketflow.event.repository.TicketTierRepository;
import com.ticketflow.inventory.DeductResult;
import com.ticketflow.inventory.InventoryStrategy;
import com.ticketflow.inventory.InventoryStrategyRegistry;
import com.ticketflow.inventory.InventoryStrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one inventory strategy against a fresh, throwaway ticket tier with N buyers released at the
 * same instant, then reports exactly what happened: who got tickets, what is left in the database,
 * and whether the two agree.
 *
 * <p>Only one run is allowed at a time so the measurements do not interfere with each other and a
 * public demo cannot be used to hammer the database.
 */
@Slf4j
@Service
public class LabService {

    static final String LAB_EVENT_STATUS = "LAB";

    private final InventoryStrategyRegistry registry;
    private final EventRepository eventRepository;
    private final TicketTierRepository ticketTierRepository;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int maxStock;
    private final int maxBuyers;
    private final int maxThreads;
    private final Semaphore runPermit = new Semaphore(1);

    public LabService(InventoryStrategyRegistry registry,
                      EventRepository eventRepository,
                      TicketTierRepository ticketTierRepository,
                      TransactionTemplate transactionTemplate,
                      @Value("${ticketflow.lab.enabled:true}") boolean enabled,
                      @Value("${ticketflow.lab.max-stock:1000}") int maxStock,
                      @Value("${ticketflow.lab.max-buyers:1000}") int maxBuyers,
                      @Value("${ticketflow.lab.max-threads:200}") int maxThreads) {
        this.registry = registry;
        this.eventRepository = eventRepository;
        this.ticketTierRepository = ticketTierRepository;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.maxStock = maxStock;
        this.maxBuyers = maxBuyers;
        this.maxThreads = maxThreads;
    }

    public LabDto.Catalog catalog() {
        assertEnabled();
        List<LabDto.StrategyInfo> strategies = Arrays.stream(InventoryStrategyType.values())
                .map(type -> {
                    InventoryStrategy strategy = registry.get(type);
                    return new LabDto.StrategyInfo(type, type.displayName(), type.description(), type.safe(),
                            strategy.isAvailable(), type == registry.activeType());
                })
                .toList();
        return new LabDto.Catalog(registry.activeType(), new LabDto.Limits(maxStock, maxBuyers), strategies);
    }

    public LabDto.RunResult run(LabDto.RunRequest request) {
        assertEnabled();
        int stock = request.stock();
        int buyers = request.buyers();
        if (stock > maxStock || buyers > maxBuyers) {
            throw new BusinessException("This server allows at most " + maxStock + " stock and " + maxBuyers + " buyers per run",
                    HttpStatus.BAD_REQUEST);
        }
        InventoryStrategy strategy = registry.get(request.strategy());
        if (!strategy.isAvailable()) {
            throw new BusinessException(request.strategy() + " needs Redis, which is not reachable. Start the stack with docker compose.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!runPermit.tryAcquire()) {
            throw new BusinessException("Another lab run is in progress, try again in a moment", HttpStatus.TOO_MANY_REQUESTS);
        }

        Long eventId = null;
        long tierId = -1;
        try {
            TicketTier tier = createLabTier(stock);
            eventId = tier.getEvent().getId();
            tierId = tier.getId();
            strategy.warmUp(tierId);
            return execute(strategy, tierId, stock, buyers);
        } finally {
            cleanUp(strategy, eventId, tierId);
            runPermit.release();
        }
    }

    private LabDto.RunResult execute(InventoryStrategy strategy, long tierId, int stock, int buyers) {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        long[] latencies = new long[buyers];
        CountDownLatch ready = new CountDownLatch(Math.min(buyers, maxThreads));
        CountDownLatch go = new CountDownLatch(1);

        long startedAt;
        long finishedAt;
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(buyers, maxThreads))) {
            for (int i = 0; i < buyers; i++) {
                int buyer = i;
                pool.submit(() -> {
                    ready.countDown();
                    awaitQuietly(go);
                    long t0 = System.nanoTime();
                    try {
                        DeductResult result = strategy.tryDeduct(tierId, 1);
                        switch (result) {
                            case SUCCESS -> succeeded.incrementAndGet();
                            case SOLD_OUT -> soldOut.incrementAndGet();
                            case CONFLICT -> conflicts.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        errors.incrementAndGet();
                        log.debug("Lab buyer failed under {}", strategy.type(), e);
                    } finally {
                        latencies[buyer] = System.nanoTime() - t0;
                    }
                });
            }
            awaitQuietly(ready);
            startedAt = System.nanoTime();
            go.countDown();
            pool.shutdown();
            // ExecutorService.close() waits for every buyer to finish.
        }
        finishedAt = System.nanoTime();

        int finalStock = ticketTierRepository.findAvailableCapacity(tierId).orElse(0);
        int won = succeeded.get();
        int expectedStock = Math.max(0, stock - won);
        boolean consistent = stock - won == finalStock;
        int phantomStock = Math.max(0, finalStock - expectedStock);
        int unsold = buyers >= stock && consistent ? finalStock : 0;
        long durationNanos = Math.max(1, finishedAt - startedAt);

        Arrays.sort(latencies);
        LabDto.RunResult result = new LabDto.RunResult(
                strategy.type(), stock, buyers,
                won, soldOut.get(), conflicts.get(), errors.get(),
                finalStock, Math.max(0, won - stock), phantomStock, consistent, unsold,
                durationNanos / 1_000_000,
                round(buyers / (durationNanos / 1e9)),
                percentileMs(latencies, 0.50), percentileMs(latencies, 0.95), percentileMs(latencies, 0.99),
                percentileMs(latencies, 1.0));
        log.info("Lab run {}", result);
        return result;
    }

    private TicketTier createLabTier(int stock) {
        return transactionTemplate.execute(status -> {
            Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
            Event event = Event.builder()
                    .title("Oversell Lab run")
                    .venue("Lab")
                    .city("Lab")
                    .category("Lab")
                    .status(LAB_EVENT_STATUS)
                    .startTime(start)
                    .endTime(start.plus(1, ChronoUnit.HOURS))
                    .build();
            TicketTier tier = TicketTier.builder()
                    .event(event)
                    .name("Lab tier")
                    .price(BigDecimal.ONE)
                    .totalCapacity(stock)
                    .availableCapacity(stock)
                    .build();
            event.getTicketTiers().add(tier);
            eventRepository.save(event);
            return tier;
        });
    }

    private void cleanUp(InventoryStrategy strategy, Long eventId, long tierId) {
        try {
            if (tierId > 0) {
                strategy.evict(tierId);
            }
            if (eventId != null) {
                transactionTemplate.executeWithoutResult(status -> eventRepository.deleteById(eventId));
            }
        } catch (RuntimeException e) {
            log.warn("Could not clean up lab event {}", eventId, e);
        }
    }

    private void assertEnabled() {
        if (!enabled) {
            throw new BusinessException("The Oversell Lab is disabled on this server", HttpStatus.NOT_FOUND);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double percentileMs(long[] sortedNanos, double percentile) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedNanos.length) - 1;
        return round(sortedNanos[Math.max(0, Math.min(index, sortedNanos.length - 1))] / 1e6);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
