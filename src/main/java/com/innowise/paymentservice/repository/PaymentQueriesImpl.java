package com.innowise.paymentservice.repository;

import com.innowise.paymentservice.entity.Payment;
import java.util.Objects;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Implementation of {@link PaymentQueries}. Spring Data finds it by its name (the name of the
 * interface plus {@code Impl}) and puts it behind {@link PaymentRepository}.
 *
 * <p>{@code MongoTemplate} itself turns the exceptions of the MongoDB driver into the exceptions
 * of Spring ({@code DuplicateKeyException} and others), so no {@code @Repository} is needed.
 */
class PaymentQueriesImpl implements PaymentQueries {

  private final MongoTemplate mongoTemplate;

  PaymentQueriesImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public Payment insert(Payment payment) {
    Objects.requireNonNull(payment, "payment");
    return mongoTemplate.insert(payment);
  }
}
