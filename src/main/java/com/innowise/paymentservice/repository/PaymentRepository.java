package com.innowise.paymentservice.repository;

import com.innowise.paymentservice.entity.Payment;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Data access of payments.
 *
 * <p>The interface extends the marker {@link Repository} instead of {@code MongoRepository} on
 * purpose and offers only what the application needs. There is no {@code save} (it would replace
 * an existing document) and no {@code delete}: a payment is created once and never changes, so
 * it cannot be changed or removed by mistake. The other operations come from
 * {@link PaymentQueries}.
 */
public interface PaymentRepository extends Repository<Payment, String>, PaymentQueries {

  /**
   * Finds a payment by its id.
   *
   * @param id the 24-character hexadecimal id; an id that is not in the collection (or is not an
   *           id at all) gives an empty result
   * @return the payment, or empty
   */
  Optional<Payment> findById(String id);
}
