package com.velocitymotors.carbooking.security;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Self-issued JWT setup: this service is both the issuer (/auth/login) and the sole
 * validator of its own tokens, signed with a shared HMAC secret - no external Identity
 * Provider involved. See README Authentication section for how this differs from (and
 * relates to) a real multi-service production setup with a central IdP.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecretKey jwtSecretKey(@Value("${app.security.jwt.secret}") String base64Secret) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Secret);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).build();
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * A single hardcoded demo account stands in for a real user store, which is out of
     * scope for this assignment - see README Authentication section.
     */
    @Bean
    public UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${app.security.demo-user.username}") String demoUsername,
            @Value("${app.security.demo-user.password}") String demoPassword) {
        UserDetails demoUser = User.withUsername(demoUsername)
                .password(passwordEncoder.encode(demoPassword))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(demoUser);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationEntryPoint entryPoint)
            throws Exception {
        http
            .csrf(csrf -> csrf.disable()) // stateless bearer-token API - no cookies/sessions to forge
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auth/**").permitAll()
                // Actuator stays open: Kubernetes' kubelet (liveness/readiness probes) and
                // Prometheus (scrape) don't send credentials. A stricter real deployment
                // would put actuator on a separate management port instead of relaxing the
                // same filter chain - a deliberate simplification, out of scope here.
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
