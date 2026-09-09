
package com.velocitymotors.carbooking.service;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.exception.InvalidVehicleException;

import lombok.extern.slf4j.Slf4j;

/**
 * Stand-in for a real vehicle/fleet service - just checks the id looks right (5-10
 * uppercase letters/digits). Swap validate() for a real call when there's an actual
 * fleet service to check against.
 */
@Slf4j
@Component
public class VehicleValidationService {

    private static final Pattern VALID_VEHICLE_ID = Pattern.compile("^[A-Z0-9]{5,10}$");

    public void validate(String vehicleId) {
        if (vehicleId == null || !VALID_VEHICLE_ID.matcher(vehicleId).matches()) {
            log.warn("Vehicle ID failed validation: {}", vehicleId);
            throw new InvalidVehicleException("Invalid vehicle ID: " + vehicleId);
        }
        log.debug("Vehicle ID passed validation: {}", vehicleId);
    }

}
