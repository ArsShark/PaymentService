package com.innowise.paymentservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.dto.event.PaymentEvent;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.mapper.PaymentEventMapper;
import com.innowise.paymentservice.messaging.KafkaTopics;
import com.innowise.paymentservice.messaging.PaymentEventPublisher;
import com.innowise.paymentservice.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The publisher against a real broker, with the real producer settings of
 * {@code application.yml}: what another service finds in the topic.
 *
 * <p>This is the contract of the event. The Order Service has the same JSON in its own tests, so
 * a change of one side that the other cannot read fails a test in both repositories. The topic is
 * shared by all tests, so every test uses order ids of its own (they are also the keys).
 */
class PaymentEventPublisherIntegrationTest extends AbstractIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(20);
  private static final Instant TIME = Instant.parse("2026-11-16T10:00:00.123Z");
  private static final String HEADER_OF_THE_JAVA_TYPE = "__TypeId__";

  /** The JSON that the Order Service reads. Keep it equal to the contract test of that service. */
  private static final String CONTRACT = """
    {
      "eventId": "%s",
      "eventType": "CREATE_PAYMENT",
      "paymentId": "%s",
      "orderId": %d,
      "userId": 7,
      "status": "%s",
      "amount": "24.00",
      "occurredAt": "2026-11-16T10:00:00.123Z"
    }
    """;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  private PaymentEventPublisher publisher;

  @Autowired
  private PaymentEventMapper eventMapper;

  @Autowired
  private PaymentRepository paymentRepository;

  private Payment savedPayment(long orderId, PaymentStatus status) {
    return paymentRepository.insert(
      new Payment(orderId, 7L, status, TIME, new BigDecimal("24.00")));
  }

  @ParameterizedTest
  @EnumSource(PaymentStatus.class)
  void theRecordInTheTopicIsTheContractOfTheEvent(PaymentStatus status) {
    long orderId = 910_000L + status.ordinal();
    Payment saved = savedPayment(orderId, status);

    publisher.publish(saved);

    List<ConsumerRecord<String, String>> records = KafkaTestReader.readByKey(
      kafkaBootstrapServers, KafkaTopics.PAYMENT_EVENTS, String.valueOf(orderId), 1, WAIT);
    assertEquals(1, records.size(), "the event did not arrive in the topic");
    ConsumerRecord<String, String> record = records.get(0);
    // The id of the event is a function of the payment, proved by the test of the mapper.
    String expected = CONTRACT.formatted(
      eventMapper.toEvent(saved).eventId(), saved.getId(), orderId, status);
    assertAll(
      () -> assertEquals(String.valueOf(orderId), record.key()),
      () -> assertEquals(KafkaTopics.PAYMENT_EVENTS, record.topic()),
      () -> assertNull(record.headers().lastHeader(HEADER_OF_THE_JAVA_TYPE),
        "the event must not carry the Java type of this service"),
      () -> assertEquals(JSON.readTree(expected), JSON.readTree(record.value())));
  }

  @Test
  void theEventsOfOneOrderGoToOnePartitionInTheOrderTheyWereSent() {
    long orderId = 910_100L;
    publisher.publish(savedPayment(orderId, PaymentStatus.FAILED));
    publisher.publish(savedPayment(orderId, PaymentStatus.SUCCESS));

    List<ConsumerRecord<String, String>> records = KafkaTestReader.readByKey(
      kafkaBootstrapServers, KafkaTopics.PAYMENT_EVENTS, String.valueOf(orderId), 2, WAIT);
    assertEquals(2, records.size(), "both events must arrive in the topic");
    ConsumerRecord<String, String> first = records.get(0);
    ConsumerRecord<String, String> second = records.get(1);
    assertAll(
      () -> assertEquals(first.partition(), second.partition()),
      () -> assertTrue(first.offset() < second.offset()),
      () -> assertEquals(PaymentStatus.FAILED,
        JSON.readValue(first.value(), PaymentEvent.class).status()),
      () -> assertEquals(PaymentStatus.SUCCESS,
        JSON.readValue(second.value(), PaymentEvent.class).status()));
  }
}
