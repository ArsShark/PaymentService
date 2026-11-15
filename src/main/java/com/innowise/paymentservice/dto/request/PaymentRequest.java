package com.innowise.paymentservice.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Payload for paying an order.
 *
 * <p>The payer, the outcome and the time are never in the body: the payer comes from the token,
 * the outcome from the random number service and the time from the clock of the service.
 *
 * <p>The amount is at least one cent and has at most two decimal places, because the entity
 * refuses more. At most ten integer digits keep it inside the largest total of an order of the
 * Order Service ({@code NUMERIC(12,2)}, 9999999999.99). The service does not check the amount
 * against the order; the Order Service does that when it receives the event.
 */
public record PaymentRequest(
  @NotNull @Positive Long orderId,
  @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
