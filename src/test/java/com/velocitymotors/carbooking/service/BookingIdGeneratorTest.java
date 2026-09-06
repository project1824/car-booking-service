package com.velocitymotors.carbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.velocitymotors.carbooking.repository.BookingRepository;

@ExtendWith(MockitoExtension.class)
class BookingIdGeneratorTest {

    @Mock
    private BookingRepository repository;

    private BookingIdGenerator generator;

    @BeforeEach
    void setUp() {
        when(repository.count()).thenReturn(0L);
        generator = new BookingIdGenerator(repository);
    }

    @Test
    void generatesTenCharacterIdWithBkgPrefix() {
        String id = generator.generate();

        assertThat(id).hasSize(10);
        assertThat(id).startsWith("BKG");
    }

    @Test
    void generatesSequentiallyIncreasingIds() {
        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).isNotEqualTo(second);
        assertThat(second).isEqualTo("BKG0000002");
    }
}
