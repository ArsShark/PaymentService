package com.innowise.paymentservice.repository;

import com.innowise.paymentservice.entity.Payment;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

  /**
   * Finds a page of the payments that match the filter.
   *
   * <p>The order of the list is always deterministic: the sort of the request is used, and the
   * id is appended as the last sort key when the request does not sort by it. Without a unique
   * last key MongoDB may return rows with equal values in a different order for every page, so
   * pages could overlap or skip payments. The names in the sort are the properties of
   * {@link Payment} ({@code timestamp}, {@code paymentAmount}, {@code orderId}, {@code userId},
   * {@code status}, {@code id}); which of them a client may use is decided by the controller.
   *
   * <p>Sorting by the id after another key cannot be served by an index alone, so MongoDB sorts
   * the matching payments in memory; a list without any filter reads the whole collection. The
   * filtered lists use the indexes of the collection (see the tests of the query plans).
   *
   * @param filter   the conditions, {@link PaymentFilter#any()} for none
   * @param pageable the page and the sort; must be paged
   * @return the page, with the total number of matching payments
   * @throws IllegalArgumentException if {@code pageable} is not paged
   */
  Page<Payment> findAll(PaymentFilter filter, Pageable pageable);

  /**
   * Adds up the successful payments of one user in a period.
   *
   * <p>Only payments with the status {@code SUCCESS} count: a declined payment moved no money.
   * The period is half-open, {@code [from, before)}: a payment made exactly at {@code from} is in,
   * one made exactly at {@code before} is not, so periods placed one after another neither
   * overlap nor leave a gap. MongoDB keeps milliseconds, so the bounds lose anything finer. An
   * empty or reversed period gives zero.
   *
   * <p>The user is a primitive {@code long} on purpose: a {@code null} that means "everybody"
   * would show a user the total of all users. The total of all users is {@link #sumOfAll}.
   *
   * @param userId the payer
   * @param from   the start of the period, inclusive
   * @param before the end of the period, exclusive
   * @return the total and the number of payments; {@code 0.00} and 0 when there are none
   */
  PaymentTotal sumOfUser(long userId, Instant from, Instant before);

  /**
   * Adds up the successful payments of all users in a period. The same rules as
   * {@link #sumOfUser}; meant for the administrator.
   *
   * @param from   the start of the period, inclusive
   * @param before the end of the period, exclusive
   * @return the total and the number of payments; {@code 0.00} and 0 when there are none
   */
  PaymentTotal sumOfAll(Instant from, Instant before);
}
