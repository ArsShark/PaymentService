package com.innowise.paymentservice.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A payment of an order: a document of the collection {@code payments}.
 *
 * <p>A payment is a record of what happened, so it never changes after it is created: there are
 * no setters. The id is the {@code _id} of the document, an {@code ObjectId} that MongoDB
 * generates when the payment is saved; in Java it is its 24-character hexadecimal string. The
 * field names of the document follow the task (snake_case); the types are fixed by the
 * validator of the collection: {@code order_id} and {@code user_id} are int64,
 * {@code payment_amount} is Decimal128.
 */
@Document(collection = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

  @Id
  private String id;

  /** The id of the order in the Order Service. There is no link to its database. */
  @Field("order_id")
  private long orderId;

  /** The id of the user in the User Service, taken from the token of the caller. */
  @Field("user_id")
  private long userId;

  private PaymentStatus status;

  /** When the payment was made. MongoDB keeps milliseconds, so the constructor cuts the rest. */
  private Instant timestamp;

  /** Stored as Decimal128, so MongoDB can add the amounts up exactly. */
  @Field(name = "payment_amount", targetType = FieldType.DECIMAL128)
  private BigDecimal paymentAmount;

  /**
   * Creates a payment that is not saved yet; the id is assigned by the first save.
   *
   * @param orderId the id of the order
   * @param userId the id of the payer
   * @param status the outcome
   * @param timestamp when the payment was made; sub-millisecond digits are dropped
   * @param paymentAmount the amount, positive
   */
  public Payment(long orderId, long userId, PaymentStatus status, Instant timestamp,
                 BigDecimal paymentAmount) {
    this.orderId = orderId;
    this.userId = userId;
    this.status = Objects.requireNonNull(status, "status");
    this.timestamp = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MILLIS);
    this.paymentAmount = Objects.requireNonNull(paymentAmount, "paymentAmount");
  }
}
