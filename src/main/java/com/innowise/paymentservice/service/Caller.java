package com.innowise.paymentservice.service;

import com.innowise.paymentservice.entity.Payment;

/**
 * Who calls the service: the profile id from the access token and whether the caller is an
 * admin. The service layer knows nothing about tokens; the controller builds this record.
 *
 * @param userId the profile id of the caller ({@code null} for an admin without a profile)
 * @param admin  whether the caller is an admin
 */
public record Caller(Long userId, boolean admin) {

  /** Tells whether the caller may see the payment: an admin, or the payer. */
  public boolean canAccess(Payment payment) {
    return admin || (userId != null && userId == payment.getUserId());
  }
}
