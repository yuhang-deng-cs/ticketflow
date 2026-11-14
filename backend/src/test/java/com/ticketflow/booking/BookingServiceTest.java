package com.ticketflow.booking;

import com.ticketflow.auth.model.User;
import com.ticketflow.booking.dto.BookingDto;
import com.ticketflow.booking.model.OrderStatus;
import com.ticketflow.booking.repository.OrderRepository;
import com.ticketflow.booking.service.BookingService;
import com.ticketflow.common.BusinessException;
import com.ticketflow.event.model.TicketTier;
import com.ticketflow.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("dev")
class BookingServiceTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private TestFixtures fixtures;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("50 buyers, 10 tickets: exactly 10 orders, stock ends at 0")
    void concurrentBookingNeverOversells() throws InterruptedException {
        User buyer = fixtures.newCustomer();
        TicketTier tier = fixtures.newTier(10);
        AtomicInteger booked = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(50, () -> {
            try {
                bookingService.createOrder(buyer, request(tier, 1));
                booked.incrementAndGet();
            } catch (BusinessException e) {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                rejected.incrementAndGet();
            }
        });

        assertThat(booked.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(40);
        assertThat(fixtures.availableCapacity(tier.getId())).isZero();
    }

    @Test
    @DisplayName("Cancelling a pending order returns its tickets; cancelling twice is rejected")
    void cancelReleasesStockOnce() {
        User buyer = fixtures.newCustomer();
        TicketTier tier = fixtures.newTier(5);
        BookingDto.OrderResponse order = bookingService.createOrder(buyer, request(tier, 2));
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(3);

        BookingDto.OrderResponse cancelled = bookingService.cancelOrder(buyer, order.getOrderNumber());

        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(5);
        assertThatThrownBy(() -> bookingService.cancelOrder(buyer, order.getOrderNumber()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(5);
    }

    @Test
    @DisplayName("Paying after the hold expired returns 410 and releases the tickets")
    void payingExpiredOrderReleasesStock() {
        User buyer = fixtures.newCustomer();
        TicketTier tier = fixtures.newTier(5);
        BookingDto.OrderResponse order = bookingService.createOrder(buyer, request(tier, 1));
        setExpiry(order.getOrderId(), Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> bookingService.payOrder(buyer, new BookingDto.PayOrderRequest(order.getOrderNumber(), "MOCK")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.GONE);

        assertThat(orderRepository.findById(order.getOrderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo(5);
    }

    @Test
    @DisplayName("Another user's order looks like it does not exist")
    void ordersAreOwnerOnly() {
        User owner = fixtures.newCustomer();
        User stranger = fixtures.newCustomer();
        BookingDto.OrderResponse order = bookingService.createOrder(owner, request(fixtures.newTier(5), 1));

        assertThatThrownBy(() -> bookingService.getOrderByNumber(stranger, order.getOrderNumber()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Regression test for the pay-vs-expiry race: a payment and the expiry sweeper hit the same order at
     * the moment its hold runs out. Before the fix both could "win": the sweeper released the tickets and
     * the payment confirmed the order anyway, so the same seat could be sold twice.
     */
    @Test
    @DisplayName("Payment racing the expiry sweeper: each order ends either paid or released, never both")
    void paymentAndExpiryNeverBothWin() throws InterruptedException {
        int orders = 30;
        User buyer = fixtures.newCustomer();
        TicketTier tier = fixtures.newTier(orders);
        List<BookingDto.OrderResponse> created = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            created.add(bookingService.createOrder(buyer, request(tier, 1)));
        }
        assertThat(fixtures.availableCapacity(tier.getId())).isZero();

        Instant deadline = Instant.now().plusMillis(300);
        created.forEach(o -> setExpiry(o.getOrderId(), deadline));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(orders * 2);
        for (BookingDto.OrderResponse order : created) {
            pool.submit(() -> {
                try {
                    sleepUntil(deadline);
                    bookingService.payOrder(buyer, new BookingDto.PayOrderRequest(order.getOrderNumber(), "MOCK"));
                } catch (BusinessException ignored) {
                    // Lost the race to the sweeper: expected for some orders.
                } finally {
                    done.countDown();
                }
            });
            pool.submit(() -> {
                try {
                    sleepUntil(deadline);
                    bookingService.expireOrder(order.getOrderId(), Instant.now());
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdown();

        long confirmed = 0;
        long expired = 0;
        for (BookingDto.OrderResponse order : created) {
            OrderStatus status = orderRepository.findById(order.getOrderId()).orElseThrow().getStatus();
            assertThat(status).isIn(OrderStatus.CONFIRMED, OrderStatus.EXPIRED);
            if (status == OrderStatus.CONFIRMED) {
                confirmed++;
            } else {
                expired++;
            }
        }
        assertThat(confirmed + expired).isEqualTo(orders);
        // Exactly the expired orders gave their seat back; confirmed seats stay sold.
        assertThat(fixtures.availableCapacity(tier.getId())).isEqualTo((int) expired);
    }

    private void setExpiry(Long orderId, Instant expiresAt) {
        transactionTemplate.executeWithoutResult(status -> {
            var order = orderRepository.findById(orderId).orElseThrow();
            order.setExpiresAt(expiresAt);
        });
    }

    private static BookingDto.CreateOrderRequest request(TicketTier tier, int quantity) {
        return new BookingDto.CreateOrderRequest(tier.getEvent().getId(), tier.getId(), quantity);
    }

    private static void runConcurrently(int threads, Runnable task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        pool.shutdown();
    }

    private static void sleepUntil(Instant instant) {
        long millis = instant.toEpochMilli() - System.currentTimeMillis();
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
