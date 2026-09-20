package com.velocitymotors.carbooking.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Clients opt in via the X-API-Version header; nobody sends it today so everyone gets
 * "1.0" by default - nothing changes for existing callers. This just means a future
 * "2.0" can be added later without breaking whatever's still calling 1.0.
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
