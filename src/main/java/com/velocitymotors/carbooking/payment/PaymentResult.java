package com.velocitymotors.carbooking.payment;

import com.velocitymotors.carbooking.enums.BookingStatus;

public record  PaymentResult(BookingStatus status){}