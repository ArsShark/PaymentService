package com.innowise.paymentservice.exception;

/**
 * Thrown when there is no such payment, or it belongs to somebody else: a caller must not be able
 * to tell the two apart, so one exception covers both.
 *
 * <p>The message is constant and does not name the id: the id comes from the URL as an arbitrary
 * text, and a response or a log line must never repeat a value of the client.
 */
public class PaymentNotFoundException extends RuntimeException {

  /** The detail sent to the client. */
  public static final String MESSAGE = "Payment not found";

  public PaymentNotFoundException() {
    super(MESSAGE);
  }
}
