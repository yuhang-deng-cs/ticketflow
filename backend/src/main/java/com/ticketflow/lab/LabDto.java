package com.ticketflow.lab;

import com.ticketflow.inventory.InventoryStrategyType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class LabDto {

    private LabDto() {
    }

    public record StrategyInfo(InventoryStrategyType type,
                               String displayName,
                               String description,
                               boolean safe,
                               boolean available,
                               boolean active) {
    }

    public record Limits(int maxStock, int maxBuyers) {
    }

    public record Catalog(InventoryStrategyType activeStrategy, Limits limits, List<StrategyInfo> strategies) {
    }

    public record RunRequest(
            @NotNull InventoryStrategyType strategy,
            @NotNull @Min(1) @Max(10_000) Integer stock,
            @NotNull @Min(1) @Max(2_000) Integer buyers) {
    }

    /**
     * Outcome of one lab run. Every buyer tries to take one ticket at the same instant.
     *
     * @param succeeded  buyers told "you got a ticket"
     * @param soldOut    buyers told "sold out"
     * @param conflicts  buyers turned away because of contention (optimistic-lock retries exhausted)
     * @param errors     buyers whose request failed unexpectedly (e.g. lock timeouts)
     * @param finalStock stock left in the database after the run
     * @param oversold   tickets promised beyond what existed: {@code max(0, succeeded - stock)}
     * @param phantomStock tickets the database still shows as free although they were already sold
     *                     (lost updates). They will be sold a second time.
     * @param consistent whether {@code stock - succeeded == finalStock}, i.e. no update was lost
     * @param unsold     tickets left over even though there were enough buyers for all of them
     */
    public record RunResult(InventoryStrategyType strategy,
                            int stock,
                            int buyers,
                            int succeeded,
                            int soldOut,
                            int conflicts,
                            int errors,
                            int finalStock,
                            int oversold,
                            int phantomStock,
                            boolean consistent,
                            int unsold,
                            long durationMs,
                            double throughputPerSecond,
                            double p50Ms,
                            double p95Ms,
                            double p99Ms,
                            double maxMs) {
    }
}
