package com.innowise.paymentservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.TestPayments;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CallerTest {

  private static final Payment PAYMENT = TestPayments.saved("65f0c0ffee0123456789abcd", 5L, 7L,
    PaymentStatus.SUCCESS, Instant.parse("2026-11-15T12:00:00Z"), "24.00");

  @Test
  void thePayerAndAnAdminMayAccessThePaymentButAnotherUserMayNot() {
    assertAll(
      () -> assertTrue(new Caller(7L, false).canAccess(PAYMENT)),
      () -> assertTrue(new Caller(1L, true).canAccess(PAYMENT)),
      () -> assertTrue(new Caller(null, true).canAccess(PAYMENT)),
      () -> assertFalse(new Caller(8L, false).canAccess(PAYMENT)));
  }

  @Test
  void theOrderIdIsNotTheOwner() {
    // The order id of the payment is 5: a caller whose profile id is 5 owns nothing.
    assertFalse(new Caller(5L, false).canAccess(PAYMENT));
  }

  @Test
  void aCallerWithoutAProfileOwnsNothing() {
    assertFalse(new Caller(null, false).canAccess(PAYMENT));
  }
}
