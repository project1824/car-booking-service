package com.velocitymotors.carbooking.dto;

import com.velocitymotors.carbooking.enums.BookingStatus;

public record BookingResponse(
    String bookingId,
    BookingStatus status
) {}
