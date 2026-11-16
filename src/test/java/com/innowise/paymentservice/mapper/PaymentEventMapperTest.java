package com.innowise.paymentservice.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.innowise.paymentservice.TestPayments;
import com.innowise.paymentservice.dto.event.PaymentEvent;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks the conversion of {@link PaymentEventMapper}. The order id (5) and the payer (7) differ
 * on purpose, so mixing them up fails a test.
 */
class PaymentEventMapperTest {

  private static final String PAYMENT_ID = "65f0c0ffee0123456789abcd";
  private static final Instant TIME = Instant.parse("2026-11-16T10:00:00.123Z");

  private final PaymentEventMapper mapper = new PaymentEventMapperImpl();

  private static Payment payment(String id, PaymentStatus status, String amount) {
    return TestPayments.saved(id, 5L, 7L, status, TIME, amount);
  }

  @ParameterizedTest
  @EnumSource(PaymentStatus.class)
  void aPaymentBecomesItsEventWithTheSameFacts(PaymentStatus status) {
    PaymentEvent event = mapper.toEvent(payment(PAYMENT_ID, status, "24.5"));

    assertAll(
      () -> assertEquals("CREATE_PAYMENT", event.eventType()),
      () -> assertEquals(PAYMENT_ID, event.paymentId()),
      () -> assertEquals(5L, event.orderId()),
      () -> assertEquals(7L, event.userId()),
      () -> assertEquals(status, event.status()),
      () -> assertEquals(TIME, event.occurredAt()));
  }

  @Test
  void theAmountIsTextWithTwoDecimalPlaces() {
    assertAll(
      () -> assertEquals("24.50", mapper.toEvent(payment(PAYMENT_ID, PaymentStatus.SUCCESS,
        "24.5")).amount()),
      () -> assertEquals("24.00", mapper.toEvent(payment(PAYMENT_ID, PaymentStatus.SUCCESS,
        "24")).amount()),
      () -> assertEquals("0.01", mapper.toEvent(payment(PAYMENT_ID, PaymentStatus.SUCCESS,
        "0.01")).amount()),
      () -> assertEquals("9999999999.99", mapper.toEvent(payment(PAYMENT_ID,
        PaymentStatus.SUCCESS, "9999999999.99")).amount()));
  }

  @Test
  void theEventIdIsTheSameForTheSamePaymentAndDiffersBetweenPayments() {
    UUID first = mapper.toEvent(payment(PAYMENT_ID, PaymentStatus.SUCCESS, "24.00")).eventId();
    UUID again = mapper.toEvent(payment(PAYMENT_ID, PaymentStatus.FAILED, "99.00")).eventId();
    UUID other = mapper.toEvent(payment("65f0c0ffee0123456789abce", PaymentStatus.SUCCESS,
      "24.00")).eventId();

    assertAll(
      () -> assertEquals(first, again),
      () -> assertNotEquals(first, other));
  }
}
