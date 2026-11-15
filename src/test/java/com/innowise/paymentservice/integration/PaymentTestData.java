package com.innowise.paymentservice.integration;

import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/** Builders of payments for the integration tests. Static helpers, no context of their own. */
final class PaymentTestData {

  /** The time of a payment that does not care about its time. */
  static final Instant NOON = Instant.parse("2026-11-14T12:00:00Z");

  private PaymentTestData() {
  }

  /** A payment of 24.00 at {@link #NOON}. */
  static Payment payment(long orderId, long userId, PaymentStatus status) {
    return payment(orderId, userId, status, NOON, "24.00");
  }

  /** A payment with every value set. */
  static Payment payment(long orderId, long userId, PaymentStatus status, Instant timestamp,
                         String amount) {
    return new Payment(orderId, userId, status, timestamp, new BigDecimal(amount));
  }
}
