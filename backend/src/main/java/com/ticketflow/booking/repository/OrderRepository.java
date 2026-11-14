package com.ticketflow.booking.repository;

import com.ticketflow.booking.model.Order;
import com.ticketflow.booking.model.OrderStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = {"user", "event", "items", "items.ticketTier"})
    Optional<Order> findByOrderNumber(String orderNumber);

    @EntityGraph(attributePaths = {"event", "items", "items.ticketTier"})
    List<Order> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.expiresAt < :now ORDER BY o.expiresAt")
    List<Long> findExpiredOrderIds(@Param("status") OrderStatus status, @Param("now") Instant now, Pageable page);

    /*
     * State transitions are compare-and-set UPDATEs, never "load, check, save".
     * Exactly one caller can move an order out of PENDING, so a payment racing the expiry
     * sweeper (or two sweeper instances racing each other) can never both win, and stock is
     * released at most once.
     */

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.ticketflow.booking.model.OrderStatus.CONFIRMED, o.updatedAt = :now " +
           "WHERE o.id = :id AND o.status = com.ticketflow.booking.model.OrderStatus.PENDING AND o.expiresAt > :now")
    int confirmIfPayable(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.ticketflow.booking.model.OrderStatus.EXPIRED, o.updatedAt = :now " +
           "WHERE o.id = :id AND o.status = com.ticketflow.booking.model.OrderStatus.PENDING AND o.expiresAt <= :now")
    int expireIfOverdue(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Order o SET o.status = com.ticketflow.booking.model.OrderStatus.CANCELLED, o.updatedAt = :now " +
           "WHERE o.id = :id AND o.status = com.ticketflow.booking.model.OrderStatus.PENDING")
    int cancelIfPending(@Param("id") Long id, @Param("now") Instant now);
}
