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
        generator = new BookingIdGenerator(repository);
    }

    @Test
    void generatesTenCharacterIdWithBkgPrefix() {
        when(repository.nextBookingIdSequence()).thenReturn(1L);

        String id = generator.generate();

        assertThat(id).hasSize(10);
        assertThat(id).startsWith("BKG");
    }

    @Test
    void scramblesTheRawSequenceValueInsteadOfUsingItDirectly() {
        // (1 * 6_700_417) % 10_000_000 = 6700417 - computed and verified independently,
        // not just asserting whatever the code happens to produce.
        when(repository.nextBookingIdSequence()).thenReturn(1L);

        String id = generator.generate();

        assertThat(id).isEqualTo("BKG6700417");
    }

    @Test
    void consecutiveSequenceValuesDoNotProduceConsecutiveOrNearbyIds() {
        when(repository.nextBookingIdSequence()).thenReturn(1L, 2L, 3L);

        String first = generator.generate();
        String second = generator.generate();
        String third = generator.generate();

        // this is the actual point of the scramble - sequence 1,2,3 must not read back
        // as booking numbers 1,2,3 (or anything else obviously incrementing).
        assertThat(first).isEqualTo("BKG6700417");
        assertThat(second).isEqualTo("BKG3400834");
        assertThat(third).isEqualTo("BKG0101251");
    }

    @Test
    void differentSequenceValuesNeverProduceTheSameId() {
        when(repository.nextBookingIdSequence()).thenReturn(1L, 2L, 3L, 4L, 5L);

        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 5; i++) {
            ids.add(generator.generate());
        }

        assertThat(ids).hasSize(5);
    }
}
