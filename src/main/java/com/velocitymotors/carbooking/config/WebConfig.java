package com.velocitymotors.carbooking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Clients opt into a specific version via the X-API-Version header; anyone who omits
 * it (all current tests included) resolves to the default version below, so this is
 * purely additive - no existing behavior changes. The real payoff is future-proofing:
 * a "2.0" handler method can be added to BookingController later without breaking
 * whatever's still calling the 1.0 contract.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.useRequestHeader("X-API-Version")
                .addSupportedVersions("1.0")
                .setDefaultVersion("1.0");
    }
}
