package com.innowise.paymentservice.entity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the rules that the constructor of {@link Payment} enforces. No database is needed. */
class PaymentTest {

  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

  private static Payment withAmount(String amount) {
    return new Payment(1L, 7L, PaymentStatus.SUCCESS, NOW, new BigDecimal(amount));
  }

  @ParameterizedTest(name = "{0} is kept as {1}")
  @CsvSource({
    "24,        24.00",
    "24.0,      24.00",
    "24.5,      24.50",
    "24.50,     24.50",
    "24.500,    24.50",
    "0.01,      0.01",
    "1E+3,      1000.00",
    "9999999999.99, 9999999999.99"
  })
  void theAmountGetsExactlyTwoDecimalPlaces(String given, String expected) {
    BigDecimal amount = withAmount(given).getPaymentAmount();

    assertAll(
      () -> assertEquals(new BigDecimal(expected), amount),
      () -> assertEquals(2, amount.scale()));
  }

  @ParameterizedTest(name = "{0} is refused")
  @ValueSource(strings = {"24.001", "0.005", "1.234567"})
  void anAmountThatWouldLoseADigitIsRefusedNotRounded(String given) {
    IllegalArgumentException refused =
      assertThrows(IllegalArgumentException.class, () -> withAmount(given));

    assertEquals("paymentAmount must have at most two decimal places", refused.getMessage());
  }

  @Test
  void theTimestampLosesWhatMongoDbCannotKeep() {
    Payment payment = new Payment(1L, 7L, PaymentStatus.FAILED,
      Instant.parse("2026-11-14T10:00:00.123456789Z"), new BigDecimal("1.00"));

    assertEquals(Instant.parse("2026-11-14T10:00:00.123Z"), payment.getTimestamp());
  }

  @Test
  void aNewPaymentHasNoIdUntilItIsSaved() {
    assertNull(withAmount("1.00").getId());
  }

  @Test
  void missingValuesAreRefused() {
    assertAll(
      () -> assertThrows(NullPointerException.class,
        () -> new Payment(1L, 7L, null, NOW, new BigDecimal("1.00"))),
      () -> assertThrows(NullPointerException.class,
        () -> new Payment(1L, 7L, PaymentStatus.SUCCESS, null, new BigDecimal("1.00"))),
      () -> assertThrows(NullPointerException.class,
        () -> new Payment(1L, 7L, PaymentStatus.SUCCESS, NOW, null)));
  }
}
