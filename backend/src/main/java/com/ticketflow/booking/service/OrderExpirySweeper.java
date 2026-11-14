package com.ticketflow.booking.service;

import com.ticketflow.booking.model.OrderStatus;
import com.ticketflow.booking.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Periodically expires unpaid holds and returns their stock.
 *
 * <p>No distributed lock is needed: {@link BookingService#expireOrder} is a compare-and-set, so if several
 * instances pick up the same order only one of them releases its stock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirySweeper {

    private static final int BATCH_SIZE = 200;

    private final OrderRepository orderRepository;
    private final BookingService bookingService;

    @Scheduled(fixedDelayString = "${ticketflow.booking.sweep-interval-ms:30000}")
    public void sweep() {
        Instant now = Instant.now();
        List<Long> overdue = orderRepository.findExpiredOrderIds(OrderStatus.PENDING, now, PageRequest.of(0, BATCH_SIZE));
        int expired = 0;
        for (Long orderId : overdue) {
            try {
                if (bookingService.expireOrder(orderId, now)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to expire order {}", orderId, e);
            }
        }
        if (expired > 0) {
            log.info("Expired {} unpaid order(s) and released their tickets", expired);
        }
    }
}
