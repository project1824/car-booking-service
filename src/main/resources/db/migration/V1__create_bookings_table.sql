CREATE TABLE bookings (
    id                 VARCHAR(10)  NOT NULL,
    customer_name      VARCHAR(255) NOT NULL,
    vehicle_id         VARCHAR(10)  NOT NULL,
    rental_start_date  DATE         NOT NULL,
    rental_end_date    DATE         NOT NULL,
    vehicle_category   VARCHAR(20)  NOT NULL,
    payment_mode       VARCHAR(20)  NOT NULL,
    payment_reference  VARCHAR(255),
    status             VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_bookings PRIMARY KEY (id)
);

-- Supports BookingRepository.existsOverlappingActiveBooking (vehicle double-booking check).
CREATE INDEX idx_bookings_vehicle_status ON bookings (vehicle_id, status);

-- Supports BookingRepository.existsByPaymentReferenceAndStatus (payment-reference-reuse check).
CREATE INDEX idx_bookings_payment_reference_status ON bookings (payment_reference, status);

-- Supports BookingRepository.findByPaymentModeAndStatus (bank-transfer cancellation scheduler).
CREATE INDEX idx_bookings_payment_mode_status ON bookings (payment_mode, status);
