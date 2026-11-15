package com.innowise.paymentservice.exception;

/**
 * Thrown when the period of a total is empty or reversed. The client gets a 400. The period is
 * compared after its bounds were cut to milliseconds, which is all that the database keeps.
 */
public class InvalidPeriodException extends RuntimeException {

  /** The detail sent to the client. */
  public static final String MESSAGE = "The period must start before it ends";

  public InvalidPeriodException() {
    super(MESSAGE);
  }
}
