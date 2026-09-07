package com.velocitymotors.carbooking.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.velocitymotors.carbooking.repository.BookingRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BankTransferPaymentEventListenerTest {

    @Mock
    private BookingRepository repository;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private BankTransferPaymentEventListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        listener = new BankTransferPaymentEventListener(repository, objectMapper, meterRegistry);
    }

    @Test
    void confirmsBookingWhenTransactionDetailsMatchPendingBooking() {
        when(repository.confirmIfPending(eq("BKG0012345"), any(Instant.class))).thenReturn(1);

        String message = """
                {"paymentId":"PAY001","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TXN987654321 BKG0012345"}
                """;

        listener.onBankTransferPaymentEvent(message);

        verify(repository).confirmIfPending(eq("BKG0012345"), any(Instant.class));
        assertThat(meterRegistry.get("bank_transfer_events_total").tag("outcome", "confirmed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void doesNotThrowWhenNoPendingBookingMatches() {
        when(repository.confirmIfPending(anyString(), any(Instant.class))).thenReturn(0);

        String message = """
                {"paymentId":"PAY002","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TXN987654321 BKG0000999"}
                """;

        listener.onBankTransferPaymentEvent(message);

        verify(repository).confirmIfPending(eq("BKG0000999"), any(Instant.class));
        assertThat(meterRegistry.get("bank_transfer_events_total").tag("outcome", "no_matching_booking").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void throwsForUnparseableJsonWithoutCallingRepository() {
        assertThatThrownBy(() -> listener.onBankTransferPaymentEvent("not valid json at all"))
                .isInstanceOf(MalformedBankTransferEventException.class);

        verify(repository, never()).confirmIfPending(anyString(), any(Instant.class));
        assertThat(meterRegistry.get("bank_transfer_events_total").tag("outcome", "unparseable").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void throwsForMessageWithTooShortTransactionDetails() {
        String message = """
                {"paymentId":"PAY003","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TOO SHORT"}
                """;

        assertThatThrownBy(() -> listener.onBankTransferPaymentEvent(message))
                .isInstanceOf(MalformedBankTransferEventException.class);

        verify(repository, never()).confirmIfPending(anyString(), any(Instant.class));
        assertThat(meterRegistry.get("bank_transfer_events_total").tag("outcome", "malformed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void extractsBookingIdFromTrailingTenCharactersRegardlessOfExtraWhitespace() {
        when(repository.confirmIfPending(eq("BKG0012345"), any(Instant.class))).thenReturn(1);

        String message = """
                {"paymentId":"PAY004","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TXN987654321    BKG0012345"}
                """;

        listener.onBankTransferPaymentEvent(message);

        verify(repository).confirmIfPending(eq("BKG0012345"), any(Instant.class));
    }
}
