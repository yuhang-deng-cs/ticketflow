package com.ticketflow.inventory;

import com.ticketflow.lab.LabDto;
import com.ticketflow.lab.LabService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races 200 buyers for 20 tickets against each strategy on the dev database (H2).
 * {@code PostgresStrategyComparisonTest} repeats this on real PostgreSQL and Redis.
 */
@SpringBootTest
@ActiveProfiles("dev")
class InventoryStrategyConcurrencyTest {

    private static final int STOCK = 20;
    private static final int BUYERS = 200;

    @Autowired
    private LabService labService;

    @ParameterizedTest(name = "{0} never oversells")
    @EnumSource(value = InventoryStrategyType.class, names = {"PESSIMISTIC_LOCK", "OPTIMISTIC_LOCK", "ATOMIC_UPDATE"})
    void safeStrategiesNeverOversell(InventoryStrategyType type) {
        LabDto.RunResult result = labService.run(new LabDto.RunRequest(type, STOCK, BUYERS));

        assertThat(result.oversold()).isZero();
        assertThat(result.consistent()).as("stock - succeeded == finalStock").isTrue();
        assertThat(result.errors()).isZero();
        assertThat(result.succeeded() + result.soldOut() + result.conflicts()).isEqualTo(BUYERS);
    }

    @ParameterizedTest(name = "{0} sells every ticket")
    @EnumSource(value = InventoryStrategyType.class, names = {"PESSIMISTIC_LOCK", "ATOMIC_UPDATE"})
    void lockingStrategiesSellOutExactly(InventoryStrategyType type) {
        LabDto.RunResult result = labService.run(new LabDto.RunRequest(type, STOCK, BUYERS));

        assertThat(result.succeeded()).isEqualTo(STOCK);
        assertThat(result.finalStock()).isZero();
        assertThat(result.soldOut()).isEqualTo(BUYERS - STOCK);
    }

    @Test
    @DisplayName("NAIVE read-then-write loses updates and oversells")
    void naiveStrategyOversells() {
        LabDto.RunResult result = labService.run(new LabDto.RunRequest(InventoryStrategyType.NAIVE, STOCK, BUYERS));

        assertThat(result.consistent()).as("lost updates leave stock that does not match sales").isFalse();
        assertThat(result.oversold()).isPositive();
    }
}
