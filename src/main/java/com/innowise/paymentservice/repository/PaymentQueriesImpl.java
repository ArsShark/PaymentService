package com.innowise.paymentservice.repository;

import com.innowise.paymentservice.entity.Payment;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * Implementation of {@link PaymentQueries}. Spring Data finds it by its name (the name of the
 * interface plus {@code Impl}) and puts it behind {@link PaymentRepository}.
 *
 * <p>{@code MongoTemplate} itself turns the exceptions of the MongoDB driver into the exceptions
 * of Spring ({@code DuplicateKeyException} and others), so no {@code @Repository} is needed.
 */
class PaymentQueriesImpl implements PaymentQueries {

  /** The property of the id; Spring Data maps it to the field {@code _id}. */
  private static final String ID = "id";

  private final MongoTemplate mongoTemplate;

  PaymentQueriesImpl(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  @Override
  public Payment insert(Payment payment) {
    Objects.requireNonNull(payment, "payment");
    return mongoTemplate.insert(payment);
  }

  @Override
  public Page<Payment> findAll(PaymentFilter filter, Pageable pageable) {
    Objects.requireNonNull(filter, "filter");
    Objects.requireNonNull(pageable, "pageable");
    if (pageable.isUnpaged()) {
      throw new IllegalArgumentException("pageable must be paged");
    }
    Pageable stable = withIdAsLastSortKey(pageable);
    List<Payment> content = mongoTemplate.find(toQuery(filter).with(stable), Payment.class);
    // The count runs only when the page alone cannot tell the total (a full page or an empty
    // page after the first).
    return PageableExecutionUtils.getPage(content, stable,
      () -> mongoTemplate.count(toQuery(filter), Payment.class));
  }

  /**
   * Builds the query of a filter. The names are the properties of the entity: Spring Data maps
   * them to the fields of the document ({@code userId} to {@code user_id}) and converts the
   * values (the enum to its name).
   */
  private static Query toQuery(PaymentFilter filter) {
    Query query = new Query();
    if (filter.userId() != null) {
      query.addCriteria(Criteria.where("userId").is(filter.userId()));
    }
    if (filter.orderId() != null) {
      query.addCriteria(Criteria.where("orderId").is(filter.orderId()));
    }
    if (filter.status() != null) {
      query.addCriteria(Criteria.where("status").is(filter.status()));
    }
    return query;
  }

  private static Pageable withIdAsLastSortKey(Pageable pageable) {
    Sort sort = pageable.getSort();
    if (sort.getOrderFor(ID) != null) {
      return pageable;
    }
    return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
      sort.and(Sort.by(ID)));
  }
}
