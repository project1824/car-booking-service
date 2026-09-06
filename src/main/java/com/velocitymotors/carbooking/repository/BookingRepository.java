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


@Modifying
@Query("""
    UPDATE Booking b
    SET b.status = com.velocitymotors.carbooking.enums.BookingStatus.CONFIRMED, b.updatedAt = :updatedAt
    WHERE b.id = :id
    AND b.status = com.velocitymotors.carbooking.enums.BookingStatus.PENDING_PAYMENT
""")
int confirmIfPending(@Param("id") String id, @Param("updatedAt") Instant updatedAt);

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
     * True if vehicleId already has a PENDING_PAYMENT or CONFIRMED booking whose date
     * range overlaps [startDate, endDate] (inclusive). CANCELLED bookings don't count -
     * cancelling frees the vehicle back up. Two ranges overlap if each range's start is
     * on or before the other range's end.
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
     * Acquires a Postgres advisory lock scoped to this vehicle id, held for the
     * remainder of the current transaction and released automatically on commit/rollback.
     * Serializes concurrent createBooking() calls for the SAME vehicle (different
     * vehicles never block each other) so the "check no overlap, then insert" sequence
     * below can't race - a plain check-then-insert alone has a window where two
     * concurrent requests could both pass the overlap check before either commits.
     * Namespaced with a "vehicle:" prefix so its hash space can't collide with
     * lockPaymentReference's.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext('vehicle:' || :vehicleId))", nativeQuery = true)
    Object lockVehicle(@Param("vehicleId") String vehicleId);

    /** Same purpose as lockVehicle, scoped to a payment reference instead. */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext('payment-ref:' || :paymentReference))", nativeQuery = true)
    Object lockPaymentReference(@Param("paymentReference") String paymentReference);
}
