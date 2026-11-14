package com.ticketflow.inventory.strategy;

import com.ticketflow.event.repository.TicketTierRepository;
import com.ticketflow.inventory.DeductResult;
import com.ticketflow.inventory.InventoryStrategy;
import com.ticketflow.inventory.InventoryStrategyType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code UPDATE ... SET stock = stock - n WHERE id = ? AND stock >= n}.
 *
 * <p>The check and the write happen inside one statement under the row lock, so there is no
 * read-modify-write window and no retry loop. The default strategy for the booking flow.
 */
@Component
@RequiredArgsConstructor
public class AtomicUpdateInventoryStrategy implements InventoryStrategy {

    private final TicketTierRepository repository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public InventoryStrategyType type() {
        return InventoryStrategyType.ATOMIC_UPDATE;
    }

    @Override
    public DeductResult tryDeduct(long tierId, int quantity) {
        Integer updated = transactionTemplate.execute(status -> repository.deductCapacityAtomically(tierId, quantity));
        return updated != null && updated == 1 ? DeductResult.SUCCESS : DeductResult.SOLD_OUT;
    }

    @Override
    public void release(long tierId, int quantity) {
        transactionTemplate.executeWithoutResult(status -> repository.releaseCapacity(tierId, quantity));
    }
}
