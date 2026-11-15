package com.innowise.paymentservice.repository;

import java.math.BigDecimal;

/**
 * The sum of the successful payments of a period.
 *
 * @param total the sum of the amounts, with two decimal places; {@code 0.00} when there are none
 * @param count how many payments were added up
 */
public record PaymentTotal(BigDecimal total, long count) {
}
