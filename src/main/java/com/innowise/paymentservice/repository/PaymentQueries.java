package com.innowise.paymentservice.repository;

import com.innowise.paymentservice.entity.Payment;

/**
 * The operations of {@link PaymentRepository} that Spring Data cannot derive from a method name.
 * They are implemented by {@link PaymentQueriesImpl} on top of {@code MongoTemplate}.
 */
public interface PaymentQueries {

  /**
   * Saves a new payment and returns it with the generated id.
   *
   * <p>This is an insert, never a replace: a payment is a record of what happened and does not
   * change afterwards. A payment that already has an id is refused by MongoDB (duplicate
   * {@code _id}) and nothing is overwritten.
   *
   * <p>The unique index {@code uq_payments_order_id_success} allows one successful payment per
   * order. The second one is refused by the database itself, which is the only check that holds
   * when two requests arrive at the same moment; the caller gets a
   * {@link org.springframework.dao.DuplicateKeyException}, and
   * {@link com.innowise.paymentservice.exception.ViolatedIndex#nameOf(Throwable)} tells which index
   * it was.
   *
   * @param payment the payment to save, not saved before
   * @return the saved payment
   * @throws org.springframework.dao.DuplicateKeyException if a unique index already holds the key
   */
  Payment insert(Payment payment);
}
