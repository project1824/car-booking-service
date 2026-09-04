
package com.velocitymotors.carbooking.service;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.velocitymotors.carbooking.exception.InvalidVehicleException;

@Component
public class VehicleValidationService {

    private static final Pattern VALID_VEHICLE_ID = Pattern.compile("^[A-Z0-9]{5,10}$");
    
    public void validate(String vehicleId) {
        if (vehicleId == null || !VALID_VEHICLE_ID.matcher(vehicleId).matches()) {
            throw new InvalidVehicleException("Invalid vehicle ID: " + vehicleId);
        }
    }

}
