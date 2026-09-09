package com.velocitymotors.carbooking.entity;

import java.time.Instant;

import com.velocitymotors.carbooking.enums.BookingStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row per Idempotency-Key a client has sent to POST /booking. bookingId/status stay
 * null until the request that first claimed this key actually finishes - see
 * BookingService.createBooking.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String key;

    @Column(name = "booking_id", length = 10)
    private String bookingId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BookingStatus status;

    @Column(nullable = false)
    private Instant createdAt;
}
