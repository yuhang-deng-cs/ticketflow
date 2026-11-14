package com.ticketflow.lab;

import com.ticketflow.inventory.InventoryStrategyRegistry;
import com.ticketflow.inventory.InventoryStrategyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class LabControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryStrategyRegistry registry;

    @Test
    void listsAllStrategiesWithoutLogin() throws Exception {
        mockMvc.perform(get("/api/v1/lab/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeStrategy").value("ATOMIC_UPDATE"))
                .andExpect(jsonPath("$.data.strategies", hasSize(5)))
                .andExpect(jsonPath("$.data.strategies[?(@.type == 'NAIVE')].safe").value(false));
    }

    @Test
    void runsAStrategyAndReportsTheOutcome() throws Exception {
        mockMvc.perform(post("/api/v1/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"strategy": "ATOMIC_UPDATE", "stock": 5, "buyers": 20}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(5))
                .andExpect(jsonPath("$.data.soldOut").value(15))
                .andExpect(jsonPath("$.data.oversold").value(0))
                .andExpect(jsonPath("$.data.consistent").value(true));
    }

    @Test
    void rejectsOutOfRangeInput() throws Exception {
        mockMvc.perform(post("/api/v1/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"strategy": "ATOMIC_UPDATE", "stock": 0, "buyers": 999999}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.stock").exists())
                .andExpect(jsonPath("$.data.buyers").exists());
    }

    @Test
    void reportsRedisStrategyAsUnavailableWithoutRedis() throws Exception {
        assumeFalse(registry.get(InventoryStrategyType.REDIS_LUA).isAvailable(), "Redis is running locally");
        mockMvc.perform(post("/api/v1/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"strategy": "REDIS_LUA", "stock": 5, "buyers": 5}
                                """))
                .andExpect(status().isServiceUnavailable());
    }
}
