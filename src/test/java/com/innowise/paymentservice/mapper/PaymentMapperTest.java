package com.innowise.paymentservice.mapper;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.innowise.paymentservice.TestPayments;
import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.dto.response.PaymentTotalResponse;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentTotal;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Checks the conversions of {@link PaymentMapper}. The order id (5) and the payer (7) differ on
 * purpose, so mixing them up fails a test.
 */
class PaymentMapperTest {

  private static final Instant TIME = Instant.parse("2026-11-15T12:00:00.123Z");

  private final PaymentMapper mapper = new PaymentMapperImpl();

  @Test
  void aPaymentBecomesItsResponseAndTheAmountIsCalledAmount() {
    Payment payment = TestPayments.saved("65f0c0ffee0123456789abcd", 5L, 7L,
      PaymentStatus.FAILED, TIME, "24.50");

    PaymentResponse response = mapper.toResponse(payment);

    assertAll(
      () -> assertEquals("65f0c0ffee0123456789abcd", response.id()),
      () -> assertEquals(5L, response.orderId()),
      () -> assertEquals(7L, response.userId()),
      () -> assertEquals(PaymentStatus.FAILED, response.status()),
      () -> assertEquals(TIME, response.timestamp()),
      () -> assertEquals(new BigDecimal("24.50"), response.amount()));
  }

  @Test
  void aRequestAndTheValuesOfTheServiceBecomeAPaymentThatIsNotSavedYet() {
    Payment payment = mapper.toEntity(new PaymentRequest(5L, new BigDecimal("24")), 7L,
      PaymentStatus.SUCCESS, TIME);

    assertAll(
      () -> assertNull(payment.getId()),
      () -> assertEquals(5L, payment.getOrderId()),
      () -> assertEquals(7L, payment.getUserId()),
      () -> assertEquals(PaymentStatus.SUCCESS, payment.getStatus()),
      () -> assertEquals(TIME, payment.getTimestamp()),
      () -> assertEquals(new BigDecimal("24.00"), payment.getPaymentAmount()));
  }

  @Test
  void anAmountWithMoreThanTwoDecimalsIsRefusedByTheEntityAndNeverRounded() {
    PaymentRequest request = new PaymentRequest(5L, new BigDecimal("1.005"));

    assertThrows(IllegalArgumentException.class,
      () -> mapper.toEntity(request, 7L, PaymentStatus.SUCCESS, TIME));
  }

  @Test
  void aTotalCarriesThePeriodThatWasCounted() {
    Instant from = Instant.parse("2026-11-01T00:00:00Z");
    Instant before = Instant.parse("2026-12-01T00:00:00Z");

    PaymentTotalResponse response = mapper.toTotalResponse(
      new PaymentTotal(new BigDecimal("124.00"), 3L), from, before);

    assertAll(
      () -> assertEquals(new BigDecimal("124.00"), response.total()),
      () -> assertEquals(3L, response.count()),
      () -> assertEquals(from, response.from()),
      () -> assertEquals(before, response.before()));
  }
}
