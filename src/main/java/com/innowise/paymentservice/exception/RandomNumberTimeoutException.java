package com.innowise.paymentservice.exception;

/**
 * Thrown when the random number service does not connect or answer in time. The client gets a
 * 504. The message is constant, for the reason given in
 * {@link RandomNumberUnavailableException}.
 */
public class RandomNumberTimeoutException extends RuntimeException {

  /** The detail sent to the client. */
  public static final String MESSAGE = "The random number service did not answer in time";

  public RandomNumberTimeoutException() {
    super(MESSAGE);
  }
}
