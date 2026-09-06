package com.velocitymotors.carbooking;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for any @SpringBootTest that needs a real database. Uses Testcontainers'
 * singleton-container pattern: the container is started once (in a static initializer,
 * never explicitly stopped) and reused across every test class that extends this one in
 * the same JVM run, rather than paying container-startup cost per test class.
 */
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("car_booking_test")
                    .withUsername("car_booking")
                    .withPassword("car_booking")
                    .withStartupTimeout(Duration.ofMinutes(3));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
