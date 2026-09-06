package com.velocitymotors.carbooking.repository;

import java.time.Instant;
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
}
