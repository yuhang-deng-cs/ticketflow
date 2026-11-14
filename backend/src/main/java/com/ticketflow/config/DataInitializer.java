package com.ticketflow.config;

import com.ticketflow.auth.model.Role;
import com.ticketflow.auth.model.User;
import com.ticketflow.auth.repository.UserRepository;
import com.ticketflow.event.model.Event;
import com.ticketflow.event.model.TicketTier;
import com.ticketflow.event.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Seeds demo accounts and, when the database has no events yet (H2 in dev), fictional demo events.
 * On PostgreSQL the events come from Flyway migrations instead.
 *
 * <p>Disable with {@code ticketflow.demo.seed=false} for any deployment that is not a demo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ticketflow.demo.seed", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements CommandLineRunner {

    private static final String IMAGE = "https://images.unsplash.com/%s?auto=format&fit=crop&w=1200&q=80";

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() == 0) {
            log.info("Seeding demo users (demo@ticketflow.test, admin@ticketflow.test)");
            userRepository.saveAll(List.of(
                    user("admin@ticketflow.test", "Demo Admin", "admin123", Role.ADMIN),
                    user("demo@ticketflow.test", "Demo Customer", "password123", Role.CUSTOMER)));
        }

        if (eventRepository.count() == 0) {
            log.info("Seeding fictional demo events");
            eventRepository.saveAll(List.of(
                    event("Neon Tides: Stadium Tour",
                            "A fictional synth-pop act on its first stadium tour. Demo data.",
                            "Accor Stadium, Sydney Olympic Park", "Sydney", "Concert",
                            "photo-1470225620780-dba8ba36b745", Duration.ofDays(14), Duration.ofHours(4),
                            tier("General Admission Standing", "149.00", 5000),
                            tier("A-Reserve Seated", "229.00", 3000),
                            tier("VIP Early Entry", "399.00", 500)),
                    event("CloudConf Sydney",
                            "A fictional two-day conference for people who build and run cloud systems. Demo data.",
                            "International Convention Centre", "Sydney", "Conference",
                            "photo-1540575467063-178a50c2df87", Duration.ofDays(30), Duration.ofDays(1),
                            tier("Standard Pass", "299.00", 2000),
                            tier("Developer All-Access + Workshop", "599.00", 800)),
                    event("Southern Circuit Motorsport Weekend",
                            "A fictional four-day motorsport festival around a lakeside street circuit. Demo data.",
                            "Lakeside Street Circuit", "Melbourne", "Sports",
                            "photo-1568605117036-5fe5e7bab0b7", Duration.ofDays(45), Duration.ofDays(2),
                            tier("Park Pass 4-Day", "195.00", 10000),
                            tier("Main Grandstand", "485.00", 1500),
                            tier("Paddock Club Hospitality", "1850.00", 200)),
                    event("Midnight Drop: Rooftop Secret Show",
                            "Only 50 tickets. Open two browser tabs and race yourself. Demo data.",
                            "Rooftop at Pier 9", "Sydney", "Concert",
                            "photo-1501386761578-eac5c94b800a", Duration.ofDays(7), Duration.ofHours(3),
                            tier("Flash Drop", "59.00", 50))));
        }
    }

    private User user(String email, String fullName, String password, Role role) {
        return User.builder()
                .email(email)
                .fullName(fullName)
                .passwordHash(passwordEncoder.encode(password))
                .role(role)
                .build();
    }

    private static Event event(String title, String description, String venue, String city, String category,
                               String unsplashPhoto, Duration startsIn, Duration length, TicketTier... tiers) {
        Instant start = Instant.now().plus(startsIn);
        Event event = Event.builder()
                .title(title)
                .description(description)
                .venue(venue)
                .city(city)
                .category(category)
                .imageUrl(IMAGE.formatted(unsplashPhoto))
                .startTime(start)
                .endTime(start.plus(length))
                .status("ACTIVE")
                .build();
        for (TicketTier tier : tiers) {
            tier.setEvent(event);
            event.getTicketTiers().add(tier);
        }
        return event;
    }

    private static TicketTier tier(String name, String price, int capacity) {
        return TicketTier.builder()
                .name(name)
                .price(new BigDecimal(price))
                .totalCapacity(capacity)
                .availableCapacity(capacity)
                .build();
    }
}
