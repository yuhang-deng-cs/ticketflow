package com.ticketflow.inventory.strategy;

import com.ticketflow.event.repository.TicketTierRepository;
import com.ticketflow.inventory.DeductResult;
import com.ticketflow.inventory.InventoryStrategy;
import com.ticketflow.inventory.InventoryStrategyType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.NoSuchElementException;

/**
 * DELIBERATELY BROKEN. Do not copy this into real code.
 *
 * <p>Reads the stock, decides in application code, then writes back the value it computed.
 * Two buyers that read the same number both "win" and the second write silently overwrites
 * the first (a lost update), so more tickets are sold than exist.
 *
 * <p>{@code ticketflow.inventory.naive-think-time-ms} simulates the work real code does between
 * the read and the write (pricing, fraud checks, logging). It widens the race window so the
 * bug reproduces on every run instead of only under production load.
 */
@Component
public class NaiveInventoryStrategy implements InventoryStrategy {

    private final TicketTierRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final long thinkTimeMs;

    public NaiveInventoryStrategy(TicketTierRepository repository,
                                  TransactionTemplate transactionTemplate,
                                  @Value("${ticketflow.inventory.naive-think-time-ms:2}") long thinkTimeMs) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.thinkTimeMs = thinkTimeMs;
    }

    @Override
    public InventoryStrategyType type() {
        return InventoryStrategyType.NAIVE;
    }

    @Override
    public DeductResult tryDeduct(long tierId, int quantity) {
        return transactionTemplate.execute(status -> {
            int available = repository.findAvailableCapacity(tierId)
                    .orElseThrow(() -> new NoSuchElementException("Ticket tier not found: " + tierId));
            if (available < quantity) {
                return DeductResult.SOLD_OUT;
            }
            simulateBusinessLogic();
            repository.overwriteAvailableCapacity(tierId, available - quantity);
            return DeductResult.SUCCESS;
        });
    }

    @Override
    public void release(long tierId, int quantity) {
        transactionTemplate.executeWithoutResult(status -> repository.releaseCapacity(tierId, quantity));
    }

    private void simulateBusinessLogic() {
        if (thinkTimeMs <= 0) {
            return;
        }
        try {
            Thread.sleep(thinkTimeMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
