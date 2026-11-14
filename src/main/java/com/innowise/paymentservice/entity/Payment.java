package com.innowise.paymentservice.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

  /** The number of decimal places of an amount, as in the order total of the Order Service. */
  public static final int AMOUNT_SCALE = 2;

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

  /**
   * Stored as Decimal128, so MongoDB can add the amounts up exactly. Always has exactly two
   * decimal places (see {@link #AMOUNT_SCALE}), so every sum of amounts has them too.
   */
  @Field(name = "payment_amount", targetType = FieldType.DECIMAL128)
  private BigDecimal paymentAmount;

  /**
   * Creates a payment that is not saved yet; the id is assigned by the first save.
   *
   * @param orderId the id of the order
   * @param userId the id of the payer
   * @param status the outcome
   * @param timestamp when the payment was made; sub-millisecond digits are dropped
   * @param paymentAmount the amount, positive; kept with exactly two decimal places, so
   *                      {@code 24} becomes {@code 24.00}
   * @throws IllegalArgumentException if the amount has more than two significant decimal places
   */
  public Payment(long orderId, long userId, PaymentStatus status, Instant timestamp,
                 BigDecimal paymentAmount) {
    this.orderId = orderId;
    this.userId = userId;
    this.status = Objects.requireNonNull(status, "status");
    this.timestamp = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MILLIS);
    this.paymentAmount = toAmountScale(Objects.requireNonNull(paymentAmount, "paymentAmount"));
  }

  /**
   * Gives the amount exactly two decimal places. Decimal128 keeps the scale, and {@code 24} and
   * {@code 24.00} are different values for {@link BigDecimal#equals(Object)}; the event for the
   * Order Service also carries the amount as text, so one scale everywhere is needed. An amount
   * that would lose a digit is refused, not rounded: nobody may pay a different sum than asked.
   */
  private static BigDecimal toAmountScale(BigDecimal amount) {
    try {
      return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("paymentAmount must have at most two decimal places", e);
    }
  }
}
