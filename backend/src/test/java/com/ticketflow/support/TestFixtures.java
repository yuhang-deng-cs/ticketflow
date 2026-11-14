package com.ticketflow.support;

import com.ticketflow.auth.model.Role;
import com.ticketflow.auth.model.User;
import com.ticketflow.auth.repository.UserRepository;
import com.ticketflow.event.model.Event;
import com.ticketflow.event.model.TicketTier;
import com.ticketflow.event.repository.EventRepository;
import com.ticketflow.event.repository.TicketTierRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Creates isolated users, events and tiers so tests never depend on seed data or on each other. */
@Component
public class TestFixtures {

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final TicketTierRepository ticketTierRepository;
    private final TransactionTemplate transactionTemplate;

    public TestFixtures(UserRepository userRepository,
                        EventRepository eventRepository,
                        TicketTierRepository ticketTierRepository,
                        TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
        this.ticketTierRepository = ticketTierRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public User newCustomer() {
        return userRepository.save(User.builder()
                .email("buyer-" + UUID.randomUUID() + "@ticketflow.test")
                .passwordHash("not-used")
                .fullName("Test Buyer")
                .role(Role.CUSTOMER)
                .build());
    }

    public TicketTier newTier(int capacity) {
        return transactionTemplate.execute(status -> {
            Instant start = Instant.now().plus(7, ChronoUnit.DAYS);
            Event event = Event.builder()
                    .title("Test event " + UUID.randomUUID())
                    .venue("Test venue")
                    .city("Sydney")
                    .category("Concert")
                    .startTime(start)
                    .endTime(start.plus(3, ChronoUnit.HOURS))
                    .status("ACTIVE")
                    .build();
            TicketTier tier = TicketTier.builder()
                    .event(event)
                    .name("Front row")
                    .price(new BigDecimal("99.00"))
                    .totalCapacity(capacity)
                    .availableCapacity(capacity)
                    .build();
            event.getTicketTiers().add(tier);
            eventRepository.save(event);
            return tier;
        });
    }

    public int availableCapacity(long tierId) {
        return ticketTierRepository.findAvailableCapacity(tierId).orElseThrow();
    }
}
