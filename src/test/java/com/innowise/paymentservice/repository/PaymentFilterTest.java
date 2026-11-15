package com.innowise.paymentservice.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innowise.paymentservice.entity.PaymentStatus;
import org.junit.jupiter.api.Test;

class PaymentFilterTest {

  @Test
  void withUserIdSetsThePayerAndKeepsTheOtherConditions() {
    PaymentFilter filter = new PaymentFilter(null, 5L, PaymentStatus.FAILED);

    assertEquals(new PaymentFilter(7L, 5L, PaymentStatus.FAILED), filter.withUserId(7L));
  }

  @Test
  void withUserIdReplacesAPayerThatWasSetBefore() {
    PaymentFilter filter = new PaymentFilter(99L, null, null);

    assertEquals(7L, filter.withUserId(7L).userId());
  }

  @Test
  void theOriginalFilterIsNotChanged() {
    PaymentFilter filter = PaymentFilter.any();

    PaymentFilter own = filter.withUserId(7L);

    assertAll(
      () -> assertEquals(PaymentFilter.any(), filter),
      () -> assertEquals(7L, own.userId()));
  }
}
