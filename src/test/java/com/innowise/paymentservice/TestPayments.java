package com.innowise.paymentservice;

import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/** Builders of payments for the unit tests. Static helpers, no Spring context. */
public final class TestPayments {

  private TestPayments() {
  }

  /**
   * A payment as it looks after it was read from the database: the id is set by reflection,
   * because the entity has no setter for it.
   */
  public static Payment saved(String id, long orderId, long userId, PaymentStatus status,
                              Instant timestamp, String amount) {
    Payment payment = new Payment(orderId, userId, status, timestamp, new BigDecimal(amount));
    ReflectionTestUtils.setField(payment, "id", id);
    return payment;
  }
}
