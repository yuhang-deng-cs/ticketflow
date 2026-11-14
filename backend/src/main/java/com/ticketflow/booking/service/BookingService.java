package com.ticketflow.booking.service;

import com.ticketflow.auth.model.User;
import com.ticketflow.booking.dto.BookingDto;
import com.ticketflow.booking.model.Order;
import com.ticketflow.booking.model.OrderItem;
import com.ticketflow.booking.model.OrderStatus;
import com.ticketflow.booking.repository.OrderRepository;
import com.ticketflow.common.BusinessException;
import com.ticketflow.event.model.TicketTier;
import com.ticketflow.event.repository.TicketTierRepository;
import com.ticketflow.inventory.DeductResult;
import com.ticketflow.inventory.InventoryStrategy;
import com.ticketflow.inventory.InventoryStrategyRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Booking flow: reserve stock → create a PENDING order with a payment deadline → pay, cancel or expire.
 *
 * <p>Stock is reserved through the configured {@link InventoryStrategy} in its own short transaction.
 * If persisting the order fails afterwards, the reservation is compensated, so a failed request
 * never leaks inventory. Order state changes are compare-and-set updates (see {@link OrderRepository}),
 * which makes payment, cancellation and the expiry sweeper safe against each other and across instances.
 */
@Slf4j
@Service
public class BookingService {

    private final OrderRepository orderRepository;
    private final TicketTierRepository ticketTierRepository;
    private final InventoryStrategyRegistry inventoryStrategies;
    private final TransactionTemplate transactionTemplate;
    private final Duration holdDuration;
    private final MeterRegistry meterRegistry;

    public BookingService(OrderRepository orderRepository,
                          TicketTierRepository ticketTierRepository,
                          InventoryStrategyRegistry inventoryStrategies,
                          TransactionTemplate transactionTemplate,
                          MeterRegistry meterRegistry,
                          @Value("${ticketflow.booking.order-hold-duration-seconds:600}") long holdSeconds) {
        this.orderRepository = orderRepository;
        this.ticketTierRepository = ticketTierRepository;
        this.inventoryStrategies = inventoryStrategies;
        this.transactionTemplate = transactionTemplate;
        this.holdDuration = Duration.ofSeconds(holdSeconds);
        this.meterRegistry = meterRegistry;
    }

    public BookingDto.OrderResponse createOrder(User user, BookingDto.CreateOrderRequest request) {
        long tierId = request.getTicketTierId();
        int quantity = request.getQuantity();
        assertTierBelongsToEvent(tierId, request.getEventId());

        InventoryStrategy inventory = inventoryStrategies.active();
        DeductResult result = inventory.tryDeduct(tierId, quantity);
        reservationCounter(inventory, result).increment();
        switch (result) {
            case SOLD_OUT -> throw new BusinessException("Sold out: not enough tickets left in this tier", HttpStatus.CONFLICT);
            case CONFLICT -> throw new BusinessException("Too many buyers right now, please retry", HttpStatus.CONFLICT);
            case SUCCESS -> { /* continue */ }
        }

        try {
            return transactionTemplate.execute(status -> persistPendingOrder(user, tierId, quantity));
        } catch (RuntimeException e) {
            inventory.release(tierId, quantity);
            log.warn("Order creation failed after stock was reserved; released {} x tier {}", quantity, tierId, e);
            throw e;
        }
    }

    public BookingDto.OrderResponse payOrder(User user, BookingDto.PayOrderRequest request) {
        Order order = findOwnedOrder(user, request.getOrderNumber());
        Instant now = Instant.now();

        Integer confirmed = transactionTemplate.execute(status -> orderRepository.confirmIfPayable(order.getId(), now));
        if (confirmed != null && confirmed == 1) {
            log.info("Order {} paid", order.getOrderNumber());
            return getOrderByNumber(user, order.getOrderNumber());
        }

        if (expireOrder(order.getId(), now)) {
            throw new BusinessException("Order hold expired; the tickets were released", HttpStatus.GONE);
        }
        OrderStatus current = findOwnedOrder(user, order.getOrderNumber()).getStatus();
        if (current == OrderStatus.EXPIRED) {
            throw new BusinessException("Order hold expired; the tickets were released", HttpStatus.GONE);
        }
        throw new BusinessException("Order is " + current + " and cannot be paid", HttpStatus.CONFLICT);
    }

    public BookingDto.OrderResponse cancelOrder(User user, String orderNumber) {
        Order order = findOwnedOrder(user, orderNumber);
        Boolean cancelled = transactionTemplate.execute(status -> {
            if (orderRepository.cancelIfPending(order.getId(), Instant.now()) == 0) {
                return false;
            }
            releaseStock(order);
            return true;
        });
        if (!Boolean.TRUE.equals(cancelled)) {
            OrderStatus current = findOwnedOrder(user, orderNumber).getStatus();
            throw new BusinessException("Order is " + current + " and cannot be cancelled", HttpStatus.CONFLICT);
        }
        log.info("Order {} cancelled", orderNumber);
        return getOrderByNumber(user, orderNumber);
    }

    /**
     * Moves an overdue PENDING order to EXPIRED and returns its stock. Safe to call concurrently:
     * only the caller whose UPDATE flips the status releases stock.
     *
     * @return whether this call performed the expiry
     */
    public boolean expireOrder(Long orderId, Instant now) {
        Boolean expired = transactionTemplate.execute(status -> {
            if (orderRepository.expireIfOverdue(orderId, now) == 0) {
                return false;
            }
            orderRepository.findById(orderId).ifPresent(this::releaseStock);
            return true;
        });
        return Boolean.TRUE.equals(expired);
    }

    @Transactional(readOnly = true)
    public List<BookingDto.OrderResponse> getUserOrders(User user) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingDto.OrderResponse getOrderByNumber(User user, String orderNumber) {
        return toResponse(findOwnedOrder(user, orderNumber));
    }

    private void assertTierBelongsToEvent(long tierId, long eventId) {
        Boolean belongs = transactionTemplate.execute(status -> ticketTierRepository.findById(tierId)
                .map(tier -> tier.getEvent().getId().equals(eventId))
                .orElseThrow(() -> new BusinessException("Ticket tier not found", HttpStatus.NOT_FOUND)));
        if (!Boolean.TRUE.equals(belongs)) {
            throw new BusinessException("Ticket tier does not belong to this event", HttpStatus.BAD_REQUEST);
        }
    }

    private BookingDto.OrderResponse persistPendingOrder(User user, long tierId, int quantity) {
        TicketTier tier = ticketTierRepository.findById(tierId)
                .orElseThrow(() -> new BusinessException("Ticket tier not found", HttpStatus.NOT_FOUND));
        BigDecimal unitPrice = tier.getPrice();
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity));

        Order order = Order.builder()
                .orderNumber(newOrderNumber())
                .user(user)
                .event(tier.getEvent())
                .totalAmount(subtotal)
                .status(OrderStatus.PENDING)
                .expiresAt(Instant.now().plus(holdDuration))
                .build();
        order.getItems().add(OrderItem.builder()
                .order(order)
                .ticketTier(tier)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .subtotal(subtotal)
                .build());

        Order saved = orderRepository.save(order);
        log.info("Order {} reserved {} x '{}' for user {} (hold {}s)",
                saved.getOrderNumber(), quantity, tier.getName(), user.getId(), holdDuration.toSeconds());
        return toResponse(saved);
    }

    private void releaseStock(Order order) {
        InventoryStrategy inventory = inventoryStrategies.active();
        for (OrderItem item : order.getItems()) {
            inventory.release(item.getTicketTier().getId(), item.getQuantity());
        }
    }

    private Order findOwnedOrder(User user, String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new BusinessException("Order not found: " + orderNumber, HttpStatus.NOT_FOUND));
        if (!order.getUser().getId().equals(user.getId())) {
            // 404 rather than 403, so order numbers cannot be probed.
            throw new BusinessException("Order not found: " + orderNumber, HttpStatus.NOT_FOUND);
        }
        return order;
    }

    /** {@code ticketflow_booking_reservations_total{strategy, result}} for the Grafana dashboard. */
    private Counter reservationCounter(InventoryStrategy inventory, DeductResult result) {
        return Counter.builder("ticketflow.booking.reservations")
                .description("Stock reservation attempts by inventory strategy and outcome")
                .tag("strategy", inventory.type().name())
                .tag("result", result.name())
                .register(meterRegistry);
    }

    private static String newOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    private BookingDto.OrderResponse toResponse(Order order) {
        List<BookingDto.OrderItemResponse> items = order.getItems().stream()
                .map(i -> BookingDto.OrderItemResponse.builder()
                        .ticketTierId(i.getTicketTier().getId())
                        .tierName(i.getTicketTier().getName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getUnitPrice())
                        .subtotal(i.getSubtotal())
                        .build())
                .toList();

        return BookingDto.OrderResponse.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .eventId(order.getEvent().getId())
                .eventTitle(order.getEvent().getTitle())
                .venue(order.getEvent().getVenue())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .expiresAt(order.getExpiresAt())
                .createdAt(order.getCreatedAt())
                .items(items)
                .build();
    }
}
