package com.innowise.paymentservice.exception;

/**
 * Thrown when an order already has a successful payment. The client gets a 409.
 *
 * <p>It is raised in two places that give the same answer: by the check before the call to the
 * random number service, and by the unique index when two payments of one order arrive together.
 */
public class OrderAlreadyPaidException extends RuntimeException {

  public OrderAlreadyPaidException(long orderId) {
    super(String.format("Order %d is already paid", orderId));
  }
}
