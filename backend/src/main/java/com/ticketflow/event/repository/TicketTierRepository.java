package com.ticketflow.event.repository;

import com.ticketflow.event.model.TicketTier;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketTierRepository extends JpaRepository<TicketTier, Long> {

    List<TicketTier> findByEventId(Long eventId);

    @Query("SELECT t.availableCapacity FROM TicketTier t WHERE t.id = :tierId")
    Optional<Integer> findAvailableCapacity(@Param("tierId") Long tierId);

    /** {@code SELECT ... FOR UPDATE}: holds a row lock until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TicketTier t WHERE t.id = :tierId")
    Optional<TicketTier> findByIdForUpdate(@Param("tierId") Long tierId);

    /**
     * Conditional atomic deduction: the database checks and decrements in one statement,
     * so two buyers can never both see the same "last ticket".
     *
     * @return 1 if stock was reserved, 0 if there was not enough
     */
    @Modifying
    @Query("UPDATE TicketTier t SET t.availableCapacity = t.availableCapacity - :qty " +
           "WHERE t.id = :tierId AND t.availableCapacity >= :qty")
    int deductCapacityAtomically(@Param("tierId") Long tierId, @Param("qty") int qty);

    @Modifying
    @Query("UPDATE TicketTier t SET t.availableCapacity = t.availableCapacity + :qty WHERE t.id = :tierId")
    int releaseCapacity(@Param("tierId") Long tierId, @Param("qty") int qty);

    /**
     * Blind overwrite used only by the deliberately broken NAIVE strategy to reproduce lost updates.
     */
    @Modifying
    @Query("UPDATE TicketTier t SET t.availableCapacity = :value WHERE t.id = :tierId")
    int overwriteAvailableCapacity(@Param("tierId") Long tierId, @Param("value") int value);
}
