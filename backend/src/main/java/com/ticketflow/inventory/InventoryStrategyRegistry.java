package com.ticketflow.inventory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up strategies by type and exposes the one the booking flow is configured to use
 * ({@code ticketflow.inventory.strategy}).
 */
@Component
public class InventoryStrategyRegistry {

    private final Map<InventoryStrategyType, InventoryStrategy> strategies = new EnumMap<>(InventoryStrategyType.class);
    private final InventoryStrategyType activeType;

    public InventoryStrategyRegistry(List<InventoryStrategy> strategies,
                                     @Value("${ticketflow.inventory.strategy:ATOMIC_UPDATE}") InventoryStrategyType activeType) {
        strategies.forEach(s -> this.strategies.put(s.type(), s));
        if (!this.strategies.containsKey(activeType)) {
            throw new IllegalStateException("No inventory strategy registered for " + activeType);
        }
        if (!activeType.safe()) {
            throw new IllegalStateException(activeType + " oversells and may only be used in the Oversell Lab");
        }
        this.activeType = activeType;
    }

    public InventoryStrategy get(InventoryStrategyType type) {
        InventoryStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unknown inventory strategy: " + type);
        }
        return strategy;
    }

    public InventoryStrategy active() {
        return strategies.get(activeType);
    }

    public InventoryStrategyType activeType() {
        return activeType;
    }

    public Collection<InventoryStrategy> all() {
        return strategies.values();
    }
}
