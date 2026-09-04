package com.velocitymotors.carbooking.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.velocitymotors.carbooking.entity.Booking;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {

}
