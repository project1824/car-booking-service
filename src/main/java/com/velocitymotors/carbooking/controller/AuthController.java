package com.velocitymotors.carbooking.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.velocitymotors.carbooking.dto.LoginRequest;
import com.velocitymotors.carbooking.dto.LoginResponse;
import com.velocitymotors.carbooking.security.JwtService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // Throws AuthenticationException (handled by GlobalExceptionHandler -> 401) for
        // an unknown user or wrong password - never distinguish the two in the response,
        // that would leak which usernames exist.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        String token = jwtService.generateToken(authentication.getName());
        log.info("Issued JWT for user={}", authentication.getName());
        return ResponseEntity.ok(new LoginResponse(token));
    }
}
