package com.ticketflow.inventory.strategy;

import com.ticketflow.event.model.TicketTier;
import com.ticketflow.event.repository.TicketTierRepository;
import com.ticketflow.inventory.DeductResult;
import com.ticketflow.inventory.InventoryStrategy;
import com.ticketflow.inventory.InventoryStrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.NoSuchElementException;

/**
 * {@code SELECT ... FOR UPDATE}: the first buyer locks the row and everyone else queues on the lock
 * until that transaction commits. Correct, but throughput is bounded by lock hold time and each waiter
 * occupies a database connection while it waits.
 */
@Component
@RequiredArgsConstructor
public class PessimisticLockInventoryStrategy implements InventoryStrategy {

    private final TicketTierRepository repository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public InventoryStrategyType type() {
        return InventoryStrategyType.PESSIMISTIC_LOCK;
    }

    @Override
    public DeductResult tryDeduct(long tierId, int quantity) {
        return transactionTemplate.execute(status -> {
            TicketTier tier = repository.findByIdForUpdate(tierId)
                    .orElseThrow(() -> new NoSuchElementException("Ticket tier not found: " + tierId));
            if (tier.getAvailableCapacity() < quantity) {
                return DeductResult.SOLD_OUT;
            }
            tier.setAvailableCapacity(tier.getAvailableCapacity() - quantity);
            return DeductResult.SUCCESS;
        });
    }

    @Override
    public void release(long tierId, int quantity) {
        transactionTemplate.executeWithoutResult(status -> repository.releaseCapacity(tierId, quantity));
    }
}
