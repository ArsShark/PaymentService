package com.innowise.paymentservice.dto.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies the constraints declared on {@link PaymentRequest}. */
class PaymentRequestValidationTest extends AbstractDtoValidationTest {

  private static final BigDecimal AMOUNT = new BigDecimal("24.00");

  @Test
  void aValidRequestHasNoViolations() {
    assertTrue(violatedProperties(new PaymentRequest(5L, AMOUNT)).isEmpty());
  }

  @Test
  void aMissingOrderIsRejected() {
    assertEquals(Set.of("orderId"), violatedProperties(new PaymentRequest(null, AMOUNT)));
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Long.MIN_VALUE})
  void anOrderIdThatIsNotPositiveIsRejected(long orderId) {
    assertEquals(Set.of("orderId"), violatedProperties(new PaymentRequest(orderId, AMOUNT)));
  }

  @Test
  void aMissingAmountIsRejected() {
    assertEquals(Set.of("amount"), violatedProperties(new PaymentRequest(5L, null)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.01", "1", "24", "24.5", "24.00", "9999999999.99"})
  void amountsFromOneCentToTheLimitOfAnOrderAreAccepted(String amount) {
    assertTrue(violatedProperties(new PaymentRequest(5L, new BigDecimal(amount))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0.00", "0.009", "-0.01", "-24"})
  void anAmountBelowOneCentIsRejected(String amount) {
    assertEquals(Set.of("amount"),
      violatedProperties(new PaymentRequest(5L, new BigDecimal(amount))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.001", "24.999", "10000000000", "10000000000.00"})
  void moreThanTwoDecimalsOrMoreThanTenIntegerDigitsIsRejected(String amount) {
    assertEquals(Set.of("amount"),
      violatedProperties(new PaymentRequest(5L, new BigDecimal(amount))));
  }

  @Test
  void bothFieldsAreReportedWhenBothAreWrong() {
    assertEquals(Set.of("orderId", "amount"),
      violatedProperties(new PaymentRequest(0L, BigDecimal.ZERO)));
  }
}
