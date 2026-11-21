package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentApiSupport.ANNA;
import static com.innowise.paymentservice.integration.PaymentApiSupport.anna;
import static com.innowise.paymentservice.integration.PaymentApiSupport.idOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.json;
import static com.innowise.paymentservice.integration.PaymentApiSupport.pay;
import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A payment through the whole application and the event on a real broker: what the Order
 * Service finds on the topic, and, as important, that a refused payment sends nothing.
 *
 * <p>The topic is shared by all tests and never emptied, so every test uses order ids of its
 * own (they are also the keys of the records). The topic is read to its end after the producer
 * was flushed ({@link #publishedEventsOf(long)}), so "none" and "exactly one" are proved, not
 * just waited for.
 */
class PaymentEventFlowIntegrationTest extends AbstractIntegrationTest {

  private static final String HEADER_OF_THE_JAVA_TYPE = "__TypeId__";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired
  private PaymentRepository paymentRepository;

  /**
   * The id of the event, written out here and not taken from the mapper: the same payment must
   * always give the same id, and the readers may count on how it is made.
   */
  private static UUID eventIdOf(String paymentId) {
    String name = String.format("CREATE_PAYMENT:%s", paymentId);
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest(name = "order {0}: the number {1} gives the event {2}")
  @CsvSource({
    "702001, 2, SUCCESS",
    "702002, 3, FAILED"})
  void thePaymentIsFollowedByItsEventOnTheTopic(long orderId, long number, PaymentStatus status)
    throws Exception {
    Instant now = Instant.parse("2026-11-19T12:34:56.789Z");
    clock.set(now);
    RandomApiStub.answersWith(number);

    MvcResult result = mockMvc.perform(pay(anna(), orderId, "24.00"))
      .andExpect(status().isCreated()).andReturn();
    String paymentId = idOf(result);

    List<ConsumerRecord<String, String>> events = publishedEventsOf(orderId);
    assertEquals(1, events.size());
    ConsumerRecord<String, String> record = events.get(0);
    JsonNode event = JSON.readTree(record.value());
    assertAll(
      () -> assertEquals(String.valueOf(orderId), record.key()),
      () -> assertNull(record.headers().lastHeader(HEADER_OF_THE_JAVA_TYPE),
        "the Java type of the event must not travel with it"),
      () -> assertEquals(8, event.size(), "the fields of the contract and no others"),
      () -> assertEquals(eventIdOf(paymentId).toString(), event.get("eventId").asString()),
      () -> assertEquals("CREATE_PAYMENT", event.get("eventType").asString()),
      () -> assertEquals(paymentId, event.get("paymentId").asString()),
      () -> assertEquals(orderId, event.get("orderId").asLong()),
      () -> assertEquals(ANNA, event.get("userId").asLong()),
      () -> assertEquals(status.name(), event.get("status").asString()),
      () -> assertTrue(event.get("amount").isString(), "the amount travels as text"),
      () -> assertEquals("24.00", event.get("amount").asString()),
      () -> assertEquals("2026-11-19T12:34:56.789Z", event.get("occurredAt").asString()));
  }

  /** The events of one order are in one partition and in the order of the payments. */
  @Test
  void theEventsOfOneOrderComeInTheOrderOfThePayments() throws Exception {
    RandomApiStub.answersInOrder(0, 3, 4);

    mockMvc.perform(pay(anna(), 702_100, "24.00")).andExpect(status().isCreated());
    mockMvc.perform(pay(anna(), 702_100, "24.00")).andExpect(status().isCreated());

    List<ConsumerRecord<String, String>> events = publishedEventsOf(702_100);
    assertEquals(2, events.size());
    assertAll(
      () -> assertEquals(events.get(0).partition(), events.get(1).partition()),
      () -> assertTrue(events.get(0).offset() < events.get(1).offset()),
      () -> assertEquals("FAILED", JSON.readTree(events.get(0).value()).get("status").asString()),
      () -> assertEquals("SUCCESS",
        JSON.readTree(events.get(1).value()).get("status").asString()));
  }

  /** A refused payment is not an event: the Order Service must never hear of it. */
  @Test
  void aPaidOrderThatIsRefusedSendsNoEvent() throws Exception {
    paymentRepository.insert(payment(702_200, 8, PaymentStatus.SUCCESS));
    RandomApiStub.answersWith(2);

    mockMvc.perform(pay(anna(), 702_200, "24.00")).andExpect(status().isConflict());

    assertEquals(0, publishedEventsOf(702_200).size());
  }

  @Test
  void aRandomNumberServiceThatFailsSendsNoEvent() throws Exception {
    RandomApiStub.answersWithStatus(503);

    mockMvc.perform(pay(anna(), 702_300, "24.00")).andExpect(status().isBadGateway());

    assertEquals(0, publishedEventsOf(702_300).size());
  }

  @Test
  void anInvalidBodySendsNoEvent() throws Exception {
    RandomApiStub.answersWith(2);

    mockMvc.perform(json(post("/api/payments")
        .header(HttpHeaders.AUTHORIZATION, bearer(anna())),
        "{\"orderId\": 702400, \"amount\": 24.001}"))
      .andExpect(status().isBadRequest());

    assertEquals(0, publishedEventsOf(702_400).size());
  }

  /** The order of a caller who may not pay is not touched, and no event leaves the service. */
  @Test
  void aCallerWithoutAProfileSendsNoEvent() throws Exception {
    RandomApiStub.answersWith(2);

    mockMvc.perform(pay(ADMIN_TOKEN, 702_500, "24.00")).andExpect(status().isForbidden());

    assertAll(
      () -> assertEquals(0, publishedEventsOf(702_500).size()),
      () -> assertEquals(0, RandomApiStub.requestCount()));
  }
}
