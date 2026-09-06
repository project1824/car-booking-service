package com.velocitymotors.carbooking.client.dto;

public record PaymentStatusResponse(
    String lastUpdateDate,
    String status
) {}
