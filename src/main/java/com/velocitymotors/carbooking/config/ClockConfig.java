
package com.velocitymotors.carbooking.config;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected instead of calling LocalDateTime.now() directly, so tests can use a fixed clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
