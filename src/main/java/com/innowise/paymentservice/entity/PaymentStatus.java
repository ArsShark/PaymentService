package com.innowise.paymentservice.entity;

/**
 * The outcome of a payment. It is stored as its name and listed in the validator of the
 * collection ({@code 001-create-payments-collection.yaml}); a new value needs a new changeSet.
 */
public enum PaymentStatus {

  /** The payment went through; the order can be marked as paid. */
  SUCCESS,

  /** The payment was declined; the order stays payable. */
  FAILED
}
