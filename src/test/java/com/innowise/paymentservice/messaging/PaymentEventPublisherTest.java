package com.innowise.paymentservice.messaging;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.paymentservice.TestPayments;
import com.innowise.paymentservice.dto.event.PaymentEvent;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.mapper.PaymentEventMapper;
import com.innowise.paymentservice.mapper.PaymentEventMapperImpl;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * Unit tests of {@link PaymentEventPublisher} with a mocked {@code KafkaTemplate}: what is sent
 * and where, and that no failure of Kafka ever reaches the caller. The real serialization and a
 * real broker are covered by {@code PaymentEventPublisherIntegrationTest}; the log line of a lost
 * event is checked together with the other log checks of JACAG-26.
 *
 * <p>The order id (5) and the payer (7) differ on purpose, so mixing them up fails a test.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

  private static final Payment PAYMENT = TestPayments.saved("65f0c0ffee0123456789abcd", 5L, 7L,
    PaymentStatus.SUCCESS, Instant.parse("2026-11-16T10:00:00.123Z"), "24.00");

  @Mock
  private KafkaTemplate<String, PaymentEvent> kafkaTemplate;

  private final PaymentEventMapper mapper = new PaymentEventMapperImpl();

  private PaymentEventPublisher publisher;

  @BeforeEach
  void createPublisher() {
    publisher = new PaymentEventPublisher(kafkaTemplate, mapper);
  }

  @SuppressWarnings("unchecked")
  private static SendResult<String, PaymentEvent> acknowledged() {
    SendResult<String, PaymentEvent> result = mock(SendResult.class);
    when(result.getRecordMetadata()).thenReturn(mock(RecordMetadata.class));
    return result;
  }

  @Test
  void theEventIsSentToThePaymentEventsTopicWithTheOrderIdAsKey() {
    when(kafkaTemplate.send(anyString(), anyString(), any(PaymentEvent.class)))
      .thenReturn(CompletableFuture.completedFuture(acknowledged()));

    publisher.publish(PAYMENT);

    ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
    verify(kafkaTemplate).send(eq("payment-events"), eq("5"), event.capture());
    assertAll(
      () -> assertEquals(mapper.toEvent(PAYMENT), event.getValue()),
      () -> assertEquals(5L, event.getValue().orderId()),
      () -> assertEquals(7L, event.getValue().userId()));
  }

  @Test
  void aSendThatFailsLaterDoesNotReachTheCaller() {
    when(kafkaTemplate.send(anyString(), anyString(), any(PaymentEvent.class)))
      .thenReturn(CompletableFuture.failedFuture(new TimeoutException("no answer")));

    assertDoesNotThrow(() -> publisher.publish(PAYMENT));
  }

  @Test
  void aSendThatThrowsAtOnceDoesNotReachTheCaller() {
    when(kafkaTemplate.send(anyString(), anyString(), any(PaymentEvent.class)))
      .thenThrow(new KafkaException("the metadata did not arrive in time"));

    assertDoesNotThrow(() -> publisher.publish(PAYMENT));
  }

  @Test
  void anEventThatCannotBeBuiltDoesNotReachTheCallerAndSendsNothing() {
    PaymentEventMapper brokenMapper = mock(PaymentEventMapper.class);
    when(brokenMapper.toEvent(PAYMENT)).thenThrow(new IllegalStateException("broken"));
    PaymentEventPublisher withBrokenMapper = new PaymentEventPublisher(kafkaTemplate,
      brokenMapper);

    assertDoesNotThrow(() -> withBrokenMapper.publish(PAYMENT));
    verifyNoInteractions(kafkaTemplate);
  }
}
