package com.innowise.paymentservice.exception;

/**
 * Thrown when the random number service cannot give a usable number: no connection, an error
 * status, a refusal because of its quota, or an answer that is not a number. The client gets a
 * 502.
 *
 * <p>The message is constant: the messages of the HTTP client hold the address of the service,
 * and the body of an answer is not ours to repeat.
 */
public class RandomNumberUnavailableException extends RuntimeException {

  /** The detail sent to the client. */
  public static final String MESSAGE = "The random number service is not available";

  public RandomNumberUnavailableException() {
    super(MESSAGE);
  }
}
