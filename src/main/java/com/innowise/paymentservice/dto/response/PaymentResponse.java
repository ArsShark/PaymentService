package com.innowise.paymentservice.dto.response;

import com.innowise.paymentservice.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment as the API shows it. A payment never changes, so there is no update time.
 *
 * @param id        the 24-character hexadecimal id
 * @param orderId   the id of the order in the Order Service
 * @param userId    the id of the payer in the User Service
 * @param status    the outcome
 * @param timestamp when the payment was made (UTC, milliseconds)
 * @param amount    the amount, with two decimal places
 */
public record PaymentResponse(
  String id,
  long orderId,
  long userId,
  PaymentStatus status,
  Instant timestamp,
  BigDecimal amount) {
}
