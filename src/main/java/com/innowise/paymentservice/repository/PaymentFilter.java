package com.innowise.paymentservice.repository;

import com.innowise.paymentservice.entity.PaymentStatus;

/**
 * The optional conditions of a list of payments. A field that is {@code null} sets no condition;
 * the others are combined with AND. A filter with no field set matches every payment.
 *
 * <p>A record with named fields instead of three parameters on purpose: two {@code Long}
 * arguments in a row are easy to swap, and the compiler would not notice.
 *
 * @param userId  the payer, or {@code null} for every payer
 * @param orderId the order, or {@code null} for every order
 * @param status  the outcome, or {@code null} for every outcome
 */
public record PaymentFilter(Long userId, Long orderId, PaymentStatus status) {

  /** A filter that matches every payment. */
  public static PaymentFilter any() {
    return new PaymentFilter(null, null, null);
  }
}
