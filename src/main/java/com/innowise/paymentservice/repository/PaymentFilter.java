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

  /**
   * Returns the same filter with the payer set, whatever it was before. The service uses it to
   * force the owner into the list of a user: a user may not look at the payments of somebody
   * else by putting another {@code userId} into the filter.
   *
   * <p>The user is a primitive {@code long} on purpose: a {@code null} would remove the
   * condition and show the payments of everybody.
   *
   * @param userId the payer
   * @return a new filter; this one is not changed
   */
  public PaymentFilter withUserId(long userId) {
    return new PaymentFilter(userId, orderId, status);
  }
}
