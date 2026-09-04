
package com.velocitymotors.carbooking.entity;

import java.time.Instant;
import java.time.LocalDate;

import com.velocitymotors.carbooking.enums.BookingStatus;
import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;

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


@Entity
@Table(name = "bookings")
@Getter 
@Setter 
@NoArgsConstructor 
@AllArgsConstructor 
@Builder 

public class Booking {

    @Id
    private String id;

    private String customerName;
    private String vehicleId;
    private LocalDate rentalStartDate;
    private LocalDate rentalEndDate;
    
    @Enumerated(EnumType.STRING)
    private VehicleCategory vehicleCategory;
    
    @Enumerated(EnumType.STRING)
    private PaymentMode paymentMode;
    
    private String paymentReference; // nullable — recall it's only meaningful for CREDIT_CARD

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    private Instant createdAt;
    private Instant updatedAt; 

}
