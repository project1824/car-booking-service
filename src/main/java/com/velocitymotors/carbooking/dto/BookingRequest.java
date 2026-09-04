
package com.velocitymotors.carbooking.dto;

import java.time.LocalDate;

import com.velocitymotors.carbooking.enums.PaymentMode;
import com.velocitymotors.carbooking.enums.VehicleCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BookingRequest(

    @NotBlank(message = "Customer name is required")
    String customerName,

    @NotBlank(message = "Vehicle ID is required")
    String vehicleId,

    @NotNull(message = "Rental start date is required")
    LocalDate rentalStartDate,

    @NotNull(message = "Rental end date is required")
    LocalDate rentalEndDate,

    @NotNull(message = "Vehicle category is required")
    VehicleCategory vehicleCategory,    
    
    @NotNull (message = "Payment mode is required")
    PaymentMode paymentMode,

    String paymentReference

) {}