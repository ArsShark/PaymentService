package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tests the question "does this order already have a successful payment?" against a real
 * MongoDB. Only the status {@code SUCCESS} counts, and only for the order that was asked about.
 */
class PaymentSuccessCheckIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private PaymentRepository repository;

  @Test
  void anOrderWithoutPaymentsHasNoSuccessfulPayment() {
    assertFalse(repository.hasSuccessfulPayment(5L));
  }

  @Test
  void anOrderWithOnlyDeclinedPaymentsHasNoSuccessfulPayment() {
    repository.insert(payment(5L, 7L, PaymentStatus.FAILED));
    repository.insert(payment(5L, 7L, PaymentStatus.FAILED));

    assertFalse(repository.hasSuccessfulPayment(5L));
  }

  @Test
  void anOrderWithASuccessfulPaymentHasOne() {
    repository.insert(payment(5L, 7L, PaymentStatus.SUCCESS));

    assertTrue(repository.hasSuccessfulPayment(5L));
  }

  @Test
  void aSuccessfulPaymentAfterDeclinedOnesIsFound() {
    repository.insert(payment(5L, 7L, PaymentStatus.FAILED));
    repository.insert(payment(5L, 7L, PaymentStatus.SUCCESS));

    assertTrue(repository.hasSuccessfulPayment(5L));
  }

  @Test
  void aSuccessfulPaymentOfAnotherOrderDoesNotCount() {
    repository.insert(payment(6L, 7L, PaymentStatus.SUCCESS));

    assertAll(
      () -> assertFalse(repository.hasSuccessfulPayment(5L)),
      () -> assertTrue(repository.hasSuccessfulPayment(6L)));
  }
}
