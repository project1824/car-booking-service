package com.velocitymotors.carbooking.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.velocitymotors.carbooking.entity.IdempotencyKey;
import com.velocitymotors.carbooking.enums.BookingStatus;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Inserts the key if nobody's claimed it yet, does nothing otherwise. Returns 1 if
     * this call just claimed it, 0 if someone else already has. A native ON CONFLICT
     * insert instead of save() + catching the unique-violation, since that violation
     * would otherwise mark the whole transaction for rollback before we get a chance to
     * look up the existing row and replay it.
     */
    @Modifying
    @Query(value = "INSERT INTO idempotency_keys (idempotency_key, created_at) VALUES (:key, :createdAt) "
            + "ON CONFLICT (idempotency_key) DO NOTHING", nativeQuery = true)
    int tryClaim(@Param("key") String key, @Param("createdAt") Instant createdAt);

    /** Fills in the result once the request that claimed this key has actually finished. */
    @Modifying
    @Query("UPDATE IdempotencyKey k SET k.bookingId = :bookingId, k.status = :status WHERE k.key = :key")
    void completeClaim(@Param("key") String key, @Param("bookingId") String bookingId, @Param("status") BookingStatus status);
}
