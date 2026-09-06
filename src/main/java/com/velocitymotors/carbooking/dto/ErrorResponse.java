package com.velocitymotors.carbooking.dto;

import java.time.Instant;


public record ErrorResponse(String message, Instant timestamp) {
}
