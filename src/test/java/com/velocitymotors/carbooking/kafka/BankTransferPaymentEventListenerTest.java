package com.velocitymotors.carbooking.kafka;

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

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class BankTransferPaymentEventListenerTest {

    @Mock
    private BookingRepository repository;

    private BankTransferPaymentEventListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        listener = new BankTransferPaymentEventListener(repository, objectMapper);
    }

    @Test
    void confirmsBookingWhenTransactionDetailsMatchPendingBooking() {
        when(repository.confirmIfPending(eq("BKG0012345"), any(Instant.class))).thenReturn(1);

        String message = """
                {"paymentId":"PAY001","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TXN987654321 BKG0012345"}
                """;

        listener.onBankTransferPaymentEvent(message);

        verify(repository).confirmIfPending(eq("BKG0012345"), any(Instant.class));
    }

    @Test
    void doesNotThrowWhenNoPendingBookingMatches() {
        when(repository.confirmIfPending(anyString(), any(Instant.class))).thenReturn(0);

        String message = """
                {"paymentId":"PAY002","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TXN987654321 BKG0000999"}
                """;

        listener.onBankTransferPaymentEvent(message);

        verify(repository).confirmIfPending(eq("BKG0000999"), any(Instant.class));
    }

    @Test
    void ignoresUnparseableJsonWithoutCallingRepository() {
        listener.onBankTransferPaymentEvent("not valid json at all");

        verify(repository, never()).confirmIfPending(anyString(), any(Instant.class));
    }

    @Test
    void ignoresMessageWithTooShortTransactionDetails() {
        String message = """
                {"paymentId":"PAY003","senderAccountNumber":"ACC123456","paymentAmount":500.00,"transactionDetails":"TOO SHORT"}
                """;

        listener.onBankTransferPaymentEvent(message);

        verify(repository, never()).confirmIfPending(anyString(), any(Instant.class));
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
