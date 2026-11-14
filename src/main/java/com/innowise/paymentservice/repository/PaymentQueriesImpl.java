package com.innowise.paymentservice.repository;

import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.TypedAggregation;
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

  /** What a period without payments adds up to: zero, with the scale of every amount. */
  private static final BigDecimal NO_MONEY = BigDecimal.ZERO.setScale(Payment.AMOUNT_SCALE);

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

  @Override
  public PaymentTotal sumOfUser(long userId, Instant from, Instant before) {
    return sum(successfulBetween(from, before).and("userId").is(userId));
  }

  @Override
  public PaymentTotal sumOfAll(Instant from, Instant before) {
    return sum(successfulBetween(from, before));
  }

  /** Successful payments made in {@code [from, before)}. */
  private static Criteria successfulBetween(Instant from, Instant before) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(before, "before");
    return Criteria.where("status").is(PaymentStatus.SUCCESS)
      .and("timestamp").gte(from).lt(before);
  }

  /**
   * Adds up the matching payments in the database: {@code $match} and then one {@code $group}
   * over all of them, which gives the sum and the count in a single round trip. MongoDB adds
   * Decimal128 values exactly, so nothing is rounded on the way. A {@code $group} over no
   * documents returns no document at all, which is the zero case.
   */
  private PaymentTotal sum(Criteria criteria) {
    TypedAggregation<Payment> aggregation = Aggregation.newAggregation(Payment.class,
      Aggregation.match(criteria),
      Aggregation.group().sum("paymentAmount").as("total").count().as("count"));

    Document result = mongoTemplate.aggregate(aggregation, Document.class)
      .getUniqueMappedResult();
    if (result == null) {
      return new PaymentTotal(NO_MONEY, 0);
    }
    return new PaymentTotal(toBigDecimal(result.get("total")),
      ((Number) result.get("count")).longValue());
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value instanceof Decimal128 decimal) {
      return decimal.bigDecimalValue();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal;
    }
    // Anything else means the amounts are not Decimal128 (a string would not even be added up).
    throw new IllegalStateException(String.format("Unexpected type of the total: %s",
      value == null ? "null" : value.getClass().getName()));
  }
}
