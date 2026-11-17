package com.innowise.paymentservice.messaging;

import com.innowise.paymentservice.dto.event.PaymentEvent;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.mapper.PaymentEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends the event {@code CREATE_PAYMENT} of a saved payment to Kafka.
 *
 * <p>Saving the payment and sending its event are two steps in two systems and nothing makes them
 * one: MongoDB has no transaction here and Kafka cannot take part in one with it. The rule of
 * this class is that a payment is never undone because its event could not be sent. So
 * {@link #publish(Payment)} never throws, and a lost event is an ERROR in the log with the ids,
 * which is enough to repair the order by hand. The event can be lost if Kafka is unreachable
 * when the first record is sent (the send gives up after {@code max.block.ms}) or stays
 * unreachable for longer than {@code delivery.timeout.ms}, or if the application stops before
 * the producer has sent the record.
 *
 * <p>The send is asynchronous: the HTTP thread waits only for the metadata of the topic (at most
 * {@code max.block.ms}), never for the delivery. The outcome is logged by the callback, on the
 * thread of the producer.
 */
@Component
public class PaymentEventPublisher {

  private static final Logger LOGGER = LoggerFactory.getLogger(PaymentEventPublisher.class);

  /** Said when the payment is saved but its event is not: the order is not told about it. */
  private static final String LOST_EVENT =
    "Event of payment {} of order {} NOT published ({}), the Order Service was not told";

  private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;
  private final PaymentEventMapper eventMapper;

  public PaymentEventPublisher(KafkaTemplate<String, PaymentEvent> kafkaTemplate,
    PaymentEventMapper eventMapper) {
    this.kafkaTemplate = kafkaTemplate;
    this.eventMapper = eventMapper;
  }

  /**
   * Sends the event of a payment that has been saved. The key is the order id, so all events of
   * one order go to one partition and are read in the order they were sent.
   *
   * @param payment a saved payment (it has an id)
   */
  public void publish(Payment payment) {
    try {
      PaymentEvent event = eventMapper.toEvent(payment);
      kafkaTemplate.send(KafkaTopics.PAYMENT_EVENTS, String.valueOf(payment.getOrderId()), event)
        .whenComplete((result, error) -> {
          if (error != null) {
            logLost(payment, error);
          } else {
            LOGGER.info("Published {} of payment {} to partition {} at offset {}",
              PaymentEvent.CREATE_PAYMENT, payment.getId(),
              result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
          }
        });
    } catch (RuntimeException e) {
      logLost(payment, e);
    }
  }

  /**
   * Logs a lost event. Only the class of the root cause is logged, never its message: the
   * message of a Kafka error can hold the address of the broker.
   */
  private static void logLost(Payment payment, Throwable error) {
    LOGGER.error(LOST_EVENT, payment.getId(), payment.getOrderId(),
      NestedExceptionUtils.getMostSpecificCause(error).getClass().getSimpleName());
  }
}
