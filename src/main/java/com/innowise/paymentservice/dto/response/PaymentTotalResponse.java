package com.innowise.paymentservice.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The sum of the successful payments of a period.
 *
 * @param total  the sum of the amounts, with two decimal places; {@code 0.00} when there are none
 * @param count  how many payments were added up
 * @param from   the start of the period that was really counted, inclusive; the request value cut
 *               to milliseconds, which is all that the database keeps
 * @param before the end of the period that was really counted, exclusive; cut the same way
 */
public record PaymentTotalResponse(
  BigDecimal total,
  long count,
  Instant from,
  Instant before) {
}
