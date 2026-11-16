package com.innowise.paymentservice.dto.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.innowise.paymentservice.entity.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The event {@code CREATE_PAYMENT} that the Payment Service sends to the topic
 * {@code payment-events} after it has saved a payment. The Order Service reads it and moves the
 * order to the next status.
 *
 * <p>This is a CONTRACT between two services that share no code: the Order Service has its own
 * copy of this record. A change that old readers cannot understand needs a new event type or a
 * new topic, not an edit. The JSON of the event is pinned by a test on both sides.
 *
 * @param eventId   id of the event; the same payment always gives the same id
 * @param eventType the kind of the event, always {@link #CREATE_PAYMENT}
 * @param paymentId id of the payment document in MongoDB
 * @param orderId   id of the paid order in the Order Service
 * @param userId    id of the payer in the User Service
 * @param status    the outcome of the payment
 * @param amount    the amount as text with two decimal places, for example {@code "24.00"}: a
 *                  JSON number would pass through a double in some readers and lose the scale
 * @param occurredAt when the payment was made (the time of the payment itself); always an
 *                  ISO-8601 string, whatever the default of the JSON mapper is
 */
public record PaymentEvent(
  UUID eventId,
  String eventType,
  String paymentId,
  long orderId,
  long userId,
  PaymentStatus status,
  String amount,
  @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {

  /** The only kind of event of this topic for now. */
  public static final String CREATE_PAYMENT = "CREATE_PAYMENT";
}
