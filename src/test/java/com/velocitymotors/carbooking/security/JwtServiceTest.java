package com.velocitymotors.carbooking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class JwtServiceTest {

    private static final SecretKey SECRET_KEY = secretKeyFromBase64("D6Z4AXKRdMVfQ7RYcURfwkthRJoiZaL6scjIZsg34A4=");

    private final JwtEncoder jwtEncoder = NimbusJwtEncoder.withSecretKey(SECRET_KEY).build();
    private final JwtDecoder jwtDecoder =
            NimbusJwtDecoder.withSecretKey(SECRET_KEY).macAlgorithm(MacAlgorithm.HS256).build();
    private final JwtService jwtService = new JwtService(jwtEncoder, 60);

    @Test
    void generatesATokenWithExpectedClaimsAndExpiryWindow() {
        Instant before = Instant.now();

        String token = jwtService.generateToken("demo");
        Jwt decoded = jwtDecoder.decode(token);

        assertThat(decoded.getSubject()).isEqualTo("demo");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("car-booking-service");
        assertThat(decoded.getExpiresAt()).isAfter(before.plus(Duration.ofMinutes(59)));
        assertThat(decoded.getExpiresAt()).isBefore(before.plus(Duration.ofMinutes(61)));
    }

    @Test
    void rejectsATokenSignedWithADifferentKey() {
        SecretKey otherKey = secretKeyFromBase64("k7h5s6q0S1o2W1u3T4y5U6i7O8p9A0s1D2f3G4h5J6k=");
        JwtService otherService = new JwtService(NimbusJwtEncoder.withSecretKey(otherKey).build(), 60);

        String tokenSignedWithOtherKey = otherService.generateToken("demo");

        assertThatThrownBy(() -> jwtDecoder.decode(tokenSignedWithOtherKey))
                .isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsATamperedToken() {
        String token = jwtService.generateToken("demo");
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertThatThrownBy(() -> jwtDecoder.decode(tampered))
                .isInstanceOf(BadJwtException.class);
    }

    private static SecretKey secretKeyFromBase64(String base64Secret) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64Secret), "HmacSHA256");
    }
}
