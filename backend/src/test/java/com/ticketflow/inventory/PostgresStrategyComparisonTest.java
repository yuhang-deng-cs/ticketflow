package com.ticketflow.inventory;

import com.ticketflow.lab.LabDto;
import com.ticketflow.lab.LabService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same race as {@link InventoryStrategyConcurrencyTest}, on the production stack: PostgreSQL 16 with
 * Flyway migrations and {@code ddl-auto=validate}, plus Redis 7. Skipped automatically when Docker is not
 * available; CI always runs it and publishes {@code target/strategy-report.md} to the job summary.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "ticketflow.jwt.secret=test-secret-that-is-long-enough-for-hs256",
        "DB_PASSWORD=unused",
        "ticketflow.idempotency.store=redis",
        "spring.datasource.hikari.maximum-pool-size=20"
})
@ActiveProfiles("prod")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresStrategyComparisonTest {

    private static final int STOCK = 100;
    private static final int BUYERS = 1000;
    private static final Path REPORT = Path.of("target", "strategy-report.md");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final List<LabDto.RunResult> RESULTS = new ArrayList<>();

    @Autowired
    private LabService labService;

    @BeforeAll
    static void resetReport() {
        RESULTS.clear();
    }

    @ParameterizedTest(name = "{0} never oversells on PostgreSQL")
    @EnumSource(value = InventoryStrategyType.class, names = "NAIVE", mode = EnumSource.Mode.EXCLUDE)
    @Order(1)
    void safeStrategiesNeverOversell(InventoryStrategyType type) {
        LabDto.RunResult result = run(type);

        assertThat(result.oversold()).isZero();
        assertThat(result.consistent()).isTrue();
        assertThat(result.errors()).isZero();
        if (type != InventoryStrategyType.OPTIMISTIC_LOCK) {
            assertThat(result.succeeded()).isEqualTo(STOCK);
        }
    }

    @Test
    @Order(2)
    @DisplayName("NAIVE oversells on PostgreSQL too")
    void naiveOversells() {
        LabDto.RunResult result = run(InventoryStrategyType.NAIVE);

        assertThat(result.consistent()).isFalse();
        assertThat(result.oversold()).isPositive();
    }

    @AfterAll
    static void writeReport() throws IOException {
        StringBuilder md = new StringBuilder()
                .append("### Oversell Lab on PostgreSQL 16 + Redis 7 (CI runner)\n\n")
                .append(BUYERS).append(" buyers race for ").append(STOCK).append(" tickets.\n\n")
                .append("| Strategy | Sold | Oversold | Phantom stock | Consistent | Unsold | Rejected (contention) | Throughput (req/s) | p50 ms | p99 ms |\n")
                .append("|---|---:|---:|---:|:---:|---:|---:|---:|---:|---:|\n");
        for (LabDto.RunResult r : RESULTS) {
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %s | %d | %d | %.0f | %.1f | %.1f |%n",
                    r.strategy(), r.succeeded(), r.oversold(), r.phantomStock(), r.consistent() ? "✅" : "❌", r.unsold(),
                    r.conflicts(), r.throughputPerSecond(), r.p50Ms(), r.p99Ms()));
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, md.toString());
    }

    private LabDto.RunResult run(InventoryStrategyType type) {
        LabDto.RunResult result = labService.run(new LabDto.RunRequest(type, STOCK, BUYERS));
        RESULTS.add(result);
        return result;
    }
}
