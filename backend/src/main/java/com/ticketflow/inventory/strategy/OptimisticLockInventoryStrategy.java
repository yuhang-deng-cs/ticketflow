package com.ticketflow.inventory.strategy;

import com.ticketflow.event.model.TicketTier;
import com.ticketflow.event.repository.TicketTierRepository;
import com.ticketflow.inventory.DeductResult;
import com.ticketflow.inventory.InventoryStrategy;
import com.ticketflow.inventory.InventoryStrategyType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.NoSuchElementException;

/**
 * Reads without locking, then commits only if the row's {@code @Version} is unchanged.
 * Losers retry up to {@code ticketflow.inventory.optimistic-max-retries} times in a fresh transaction.
 *
 * <p>Never oversells, but under a hot-row stampede most attempts collide, so buyers can be turned
 * away ({@link DeductResult#CONFLICT}) while tickets are still left. The Oversell Lab reports this
 * as "unsold".
 */
@Component
public class OptimisticLockInventoryStrategy implements InventoryStrategy {

    private final TicketTierRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final int maxRetries;

    public OptimisticLockInventoryStrategy(TicketTierRepository repository,
                                           TransactionTemplate transactionTemplate,
                                           @Value("${ticketflow.inventory.optimistic-max-retries:3}") int maxRetries) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.maxRetries = maxRetries;
    }

    @Override
    public InventoryStrategyType type() {
        return InventoryStrategyType.OPTIMISTIC_LOCK;
    }

    @Override
    public DeductResult tryDeduct(long tierId, int quantity) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return transactionTemplate.execute(status -> attemptOnce(tierId, quantity));
            } catch (OptimisticLockingFailureException e) {
                // Someone else committed first; re-read and try again.
            }
        }
        return DeductResult.CONFLICT;
    }

    private DeductResult attemptOnce(long tierId, int quantity) {
        TicketTier tier = repository.findById(tierId)
                .orElseThrow(() -> new NoSuchElementException("Ticket tier not found: " + tierId));
        if (tier.getAvailableCapacity() < quantity) {
            return DeductResult.SOLD_OUT;
        }
        tier.setAvailableCapacity(tier.getAvailableCapacity() - quantity);
        // Flush inside the callback so a version clash surfaces here, as an OptimisticLockingFailureException.
        repository.saveAndFlush(tier);
        return DeductResult.SUCCESS;
    }

    @Override
    public void release(long tierId, int quantity) {
        transactionTemplate.executeWithoutResult(status -> repository.releaseCapacity(tierId, quantity));
    }
}
