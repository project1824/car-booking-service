package com.velocitymotors.carbooking.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.velocitymotors.carbooking.entity.Booking;
import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {


/**
 * Only flips PENDING_PAYMENT to CONFIRMED - a plain read-then-save here could race
 * with the cancellation scheduler and confirm a booking that just got cancelled.
 * Returns 0 if nothing matched (already confirmed/cancelled, or no such booking).
 */
@Modifying
@Query("""
    UPDATE Booking b
    SET b.status = com.velocitymotors.carbooking.enums.BookingStatus.CONFIRMED, b.updatedAt = :updatedAt
    WHERE b.id = :id
    AND b.status = com.velocitymotors.carbooking.enums.BookingStatus.PENDING_PAYMENT
""")
int confirmIfPending(@Param("id") String id, @Param("updatedAt") Instant updatedAt);

/** Same idea as confirmIfPending, the other direction - only cancels if still pending. */
@Modifying
@Query("""
    UPDATE Booking b
    SET b.status = com.velocitymotors.carbooking.enums.BookingStatus.CANCELLED, b.updatedAt = :updatedAt
    WHERE b.id = :id
    AND b.status = com.velocitymotors.carbooking.enums.BookingStatus.PENDING_PAYMENT
""")
int cancelIfPending(@Param("id") String id, @Param("updatedAt") Instant updatedAt);



    List<Booking> findByPaymentModeAndStatus(PaymentMode paymentMode, BookingStatus status);

    /**
     * True if this vehicle already has a PENDING_PAYMENT or CONFIRMED booking overlapping
     * this date range. Cancelled bookings don't count - cancelling frees the vehicle up.
     */
    @Query("""
        SELECT COUNT(b) > 0 FROM Booking b
        WHERE b.vehicleId = :vehicleId
        AND b.status IN (com.velocitymotors.carbooking.enums.BookingStatus.PENDING_PAYMENT, com.velocitymotors.carbooking.enums.BookingStatus.CONFIRMED)
        AND b.rentalStartDate <= :endDate
        AND b.rentalEndDate >= :startDate
    """)
    boolean existsOverlappingActiveBooking(
            @Param("vehicleId") String vehicleId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    boolean existsByPaymentReferenceAndStatus(String paymentReference, BookingStatus status);

    /**
     * A postgres advisory lock scoped to one vehicle id, held until the transaction ends.
     * Serializes concurrent bookings for the SAME vehicle (different vehicles never
     * block each other) so "check no overlap, then insert" can't race. "vehicle:" prefix
     * keeps it from colliding with lockPaymentReference's keys.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext('vehicle:' || :vehicleId))", nativeQuery = true)
    Object lockVehicle(@Param("vehicleId") String vehicleId);

    /** Same as lockVehicle, just keyed by payment reference instead. */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext('payment-ref:' || :paymentReference))", nativeQuery = true)
    Object lockPaymentReference(@Param("paymentReference") String paymentReference);

    /**
     * Backs BookingIdGenerator. A real postgres sequence, not an in-memory counter, so
     * two app instances calling this at the same time still always get different values -
     * nextval() is atomic at the database level, no lock needed on our side.
     */
    @Query(value = "SELECT nextval('booking_id_seq')", nativeQuery = true)
    long nextBookingIdSequence();
}
