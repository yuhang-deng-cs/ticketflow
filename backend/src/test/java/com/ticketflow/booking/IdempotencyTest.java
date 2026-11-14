package com.ticketflow.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketflow.auth.dto.AuthDto;
import com.ticketflow.booking.dto.BookingDto;
import com.ticketflow.event.model.TicketTier;
import com.ticketflow.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class IdempotencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestFixtures fixtures;

    private String token;
    private TicketTier tier;

    @BeforeEach
    void setUp() throws Exception {
        token = login("demo@ticketflow.test", "password123");
        tier = fixtures.newTier(10);
    }

    @Test
    @DisplayName("Retrying with the same key replays the first response instead of booking twice")
    void retryReplaysFirstResponse() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = book(key, 1)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        String second = book(key, 1)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(orderNumber(second)).isEqualTo(orderNumber(first));
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(9);
    }

    @Test
    @DisplayName("Reusing a key for a different request is rejected with 422")
    void keyReuseWithDifferentBodyIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        book(key, 1).andExpect(status().isOk());

        book(key, 2)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(9);
    }

    @Test
    @DisplayName("Keys are scoped per user: two users can use the same key independently")
    void keysAreScopedPerUser() throws Exception {
        String key = "shared-key-" + UUID.randomUUID();
        String otherToken = login("admin@ticketflow.test", "admin123");

        String mine = book(key, 1).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String theirs = bookAs(otherToken, key, 1)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();

        assertThat(orderNumber(theirs)).isNotEqualTo(orderNumber(mine));
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(8);
    }

    @Test
    @DisplayName("Without a key every request is a new booking")
    void noKeyMeansNoDeduplication() throws Exception {
        book(null, 1).andExpect(status().isOk());
        book(null, 1).andExpect(status().isOk());
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(8);
    }

    private ResultActions book(String key, int quantity) throws Exception {
        return bookAs(token, key, quantity);
    }

    private ResultActions bookAs(String bearer, String key, int quantity) throws Exception {
        var request = post("/api/v1/orders")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new BookingDto.CreateOrderRequest(tier.getEvent().getId(), tier.getId(), quantity)));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthDto.LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private String orderNumber(String responseBody) throws Exception {
        JsonNode node = objectMapper.readTree(responseBody);
        return node.path("data").path("orderNumber").asText();
    }
}
