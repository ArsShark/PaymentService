package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentApiSupport.ANNA;
import static com.innowise.paymentservice.integration.PaymentApiSupport.BORIS;
import static com.innowise.paymentservice.integration.PaymentApiSupport.BOSS;
import static com.innowise.paymentservice.integration.PaymentApiSupport.anna;
import static com.innowise.paymentservice.integration.PaymentApiSupport.boris;
import static com.innowise.paymentservice.integration.PaymentApiSupport.boss;
import static com.innowise.paymentservice.integration.PaymentApiSupport.detailOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.idOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.json;
import static com.innowise.paymentservice.integration.PaymentApiSupport.pay;
import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.http.Fault;
import com.innowise.paymentservice.config.RandomApiProperties;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.exception.RandomNumberTimeoutException;
import com.innowise.paymentservice.exception.RandomNumberUnavailableException;
import com.innowise.paymentservice.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A payment from the HTTP request to the document in a real MongoDB, with the random number
 * service played by WireMock ({@link RandomApiStub}): the outcome that the number decides, what
 * is stored, and what every refusal leaves behind (nothing).
 *
 * <p>The events on Kafka are in {@code PaymentEventFlowIntegrationTest}; here only the timeout
 * test also looks at the topic. Every test that needs the topic uses an order id of its own,
 * because the topic is shared and never emptied.
 */
class PaymentApiIntegrationTest extends AbstractIntegrationTest {

  /**
   * How long the stub holds the answer in the timeout test: more than the read timeout of the
   * client (5 s by default) with room to spare, so the client always gives up first and the
   * test does not depend on the speed of the machine.
   */
  private static final int STUCK_MILLIS = 8000;

  @Autowired
  private RandomApiProperties randomApiProperties;

  @Autowired
  private PaymentRepository paymentRepository;

  private long storedPayments() {
    return mongoTemplate.getCollection(PAYMENTS).countDocuments();
  }

  private long storedPaymentsOf(long orderId) {
    return mongoTemplate.getCollection(PAYMENTS).countDocuments(new Document("order_id", orderId));
  }

  private Document storedPayment(String id) {
    return mongoTemplate.getCollection(PAYMENTS).find(new Document("_id", new ObjectId(id)))
      .first();
  }

  @Test
  void anEvenNumberIsASuccessfulPaymentStoredWithTheTimeOfTheClock() throws Exception {
    Instant now = Instant.parse("2026-11-19T12:34:56.789Z");
    clock.set(now);
    RandomApiStub.answersWith(42);

    MvcResult result = mockMvc.perform(pay(anna(), 701_001, "24.00")).andExpectAll(
      status().isCreated(),
      jsonPath("$.orderId").value(701_001),
      jsonPath("$.userId").value(ANNA),
      jsonPath("$.status").value("SUCCESS"),
      jsonPath("$.timestamp").value("2026-11-19T12:34:56.789Z"),
      jsonPath("$.amount").value(24.00)).andReturn();

    String id = idOf(result);
    Document stored = storedPayment(id);
    assertAll(
      () -> assertTrue(id.matches("[0-9a-f]{24}"), id),
      () -> assertEquals(String.format("http://localhost/api/payments/%s", id),
        result.getResponse().getHeader(HttpHeaders.LOCATION)),
      () -> assertEquals(1, RandomApiStub.requestCount()),
      () -> assertEquals(1, storedPayments()),
      () -> assertEquals(701_001L, stored.get("order_id")),
      () -> assertEquals(ANNA, stored.get("user_id")),
      () -> assertEquals("SUCCESS", stored.get("status")),
      () -> assertEquals(Date.from(now), stored.get("timestamp")),
      () -> assertEquals(new Decimal128(new BigDecimal("24.00")), stored.get("payment_amount")));
  }

  /** Odd means FAILED, also below zero, where {@code number % 2} is -1 and not 1. */
  @ParameterizedTest(name = "{0} gives {1}")
  @CsvSource({
    "2, SUCCESS",
    "3, FAILED",
    "0, SUCCESS",
    "-3, FAILED",
    "-4, SUCCESS",
    "99, FAILED",
    "9223372036854775807, FAILED",
    "-9223372036854775808, SUCCESS"})
  void theParityOfTheNumberDecidesTheOutcome(long number, PaymentStatus expected)
    throws Exception {
    RandomApiStub.answersWith(number);

    MvcResult result = mockMvc.perform(pay(anna(), 701_100, "10.50")).andExpectAll(
      status().isCreated(),
      jsonPath("$.status").value(expected.name()),
      jsonPath("$.amount").value(10.50)).andReturn();

    assertAll(
      () -> assertEquals(1, RandomApiStub.requestCount()),
      () -> assertEquals(1, storedPayments()),
      () -> assertEquals(expected.name(), storedPayment(idOf(result)).get("status")));
  }

  /** A failed payment is data, not an error: it is stored, and the order may be paid again. */
  @Test
  void aFailedPaymentLeavesTheOrderOpenForANewTryThatMayPayItOnce() throws Exception {
    RandomApiStub.answersInOrder(0, 3, 4, 6);

    mockMvc.perform(pay(anna(), 701_200, "24.00"))
      .andExpectAll(status().isCreated(), jsonPath("$.status").value("FAILED"));
    mockMvc.perform(pay(anna(), 701_200, "24.00"))
      .andExpectAll(status().isCreated(), jsonPath("$.status").value("SUCCESS"));
    MvcResult third = mockMvc.perform(pay(anna(), 701_200, "24.00"))
      .andExpect(status().isConflict()).andReturn();

    assertAll(
      () -> assertEquals("Order 701200 is already paid", detailOf(third.getResponse())),
      () -> assertEquals(2, storedPaymentsOf(701_200)),
      () -> assertEquals(1, mongoTemplate.getCollection(PAYMENTS)
        .countDocuments(new Document("order_id", 701_200L).append("status", "SUCCESS"))),
      () -> assertEquals(1, mongoTemplate.getCollection(PAYMENTS)
        .countDocuments(new Document("order_id", 701_200L).append("status", "FAILED"))));
  }

  /**
   * A paid order is refused BEFORE the random number service is asked: the answer is always the
   * same 409, whatever number would have come, and the quota of the service is not used.
   */
  @Test
  void anOrderThatIsAlreadyPaidIsRefusedWithoutAskingTheRandomNumberService() throws Exception {
    paymentRepository.insert(payment(701_300, BORIS, PaymentStatus.SUCCESS));
    RandomApiStub.answersWith(2);

    MvcResult result = mockMvc.perform(pay(anna(), 701_300, "24.00"))
      .andExpectAll(status().isConflict(), problemJson()).andReturn();

    assertAll(
      () -> assertEquals("Order 701300 is already paid", detailOf(result.getResponse())),
      () -> assertEquals(0, RandomApiStub.requestCount()),
      () -> assertEquals(1, storedPaymentsOf(701_300)));
  }

  static Stream<Arguments> failuresOfTheRandomNumberService() {
    return Stream.of(
      Arguments.of("a 500", (Runnable) () -> RandomApiStub.answersWithStatus(500)),
      Arguments.of("a 503", (Runnable) () -> RandomApiStub.answersWithStatus(503)),
      Arguments.of("a 404", (Runnable) () -> RandomApiStub.answersWithStatus(404)),
      Arguments.of("a text instead of a number",
        (Runnable) () -> RandomApiStub.answersWithText("Error: quota is used up")),
      Arguments.of("an empty answer", (Runnable) () -> RandomApiStub.answersWithText("")),
      Arguments.of("a broken connection",
        (Runnable) () -> RandomApiStub.answersWithFault(Fault.CONNECTION_RESET_BY_PEER)),
      Arguments.of("garbage on the wire",
        (Runnable) () -> RandomApiStub.answersWithFault(Fault.MALFORMED_RESPONSE_CHUNK)));
  }

  @ParameterizedTest(name = "{0} is a 502 and saves nothing")
  @MethodSource("failuresOfTheRandomNumberService")
  void aRandomNumberServiceThatCannotGiveANumberIsA502AndNothingIsSaved(String name,
    Runnable stub) throws Exception {
    stub.run();

    MvcResult result = mockMvc.perform(pay(anna(), 701_400, "24.00"))
      .andExpectAll(status().isBadGateway(), problemJson()).andReturn();

    assertAll(
      () -> assertEquals(RandomNumberUnavailableException.MESSAGE,
        detailOf(result.getResponse())),
      // The HTTP client may send a request again when a pooled connection was closed, so the
      // service may have seen it twice; what matters is that it was asked and nothing was saved.
      () -> assertTrue(RandomApiStub.requestCount() >= 1),
      () -> assertEquals(0, storedPayments()));
  }

  /**
   * The stub holds the answer longer than the client waits, so the client is the one that gives
   * up. This test takes as long as the read timeout of the client (5 s by default); it is the
   * price of using the real timeout instead of a special setting of the shared context.
   */
  @Test
  void aRandomNumberServiceThatDoesNotAnswerInTimeIsA504AndNothingIsSavedOrSent()
    throws Exception {
    assertTrue(randomApiProperties.readTimeout().toMillis() < STUCK_MILLIS - 1000,
      "the stub must hold the answer clearly longer than the read timeout of the client");
    RandomApiStub.answersAfter(2, STUCK_MILLIS);

    MvcResult result = mockMvc.perform(pay(anna(), 701_500, "24.00"))
      .andExpectAll(status().isGatewayTimeout(), problemJson()).andReturn();

    assertAll(
      () -> assertEquals(RandomNumberTimeoutException.MESSAGE, detailOf(result.getResponse())),
      () -> assertEquals(0, storedPayments()),
      () -> assertEquals(0, publishedEventsOf(701_500).size()));
  }

  static Stream<Arguments> invalidBodies() {
    return Stream.of(
      Arguments.of("an empty object", "{}"),
      Arguments.of("no amount", "{\"orderId\": 5}"),
      Arguments.of("no order", "{\"amount\": 24.00}"),
      Arguments.of("an order id of zero", "{\"orderId\": 0, \"amount\": 24.00}"),
      Arguments.of("a negative order id", "{\"orderId\": -5, \"amount\": 24.00}"),
      Arguments.of("an amount of zero", "{\"orderId\": 5, \"amount\": 0}"),
      Arguments.of("less than a cent", "{\"orderId\": 5, \"amount\": 0.001}"),
      Arguments.of("a negative amount", "{\"orderId\": 5, \"amount\": -1.00}"),
      Arguments.of("three decimal places", "{\"orderId\": 5, \"amount\": 24.001}"),
      Arguments.of("eleven integer digits", "{\"orderId\": 5, \"amount\": 12345678901.00}"),
      Arguments.of("a text instead of a number", "{\"orderId\": \"five\", \"amount\": 24.00}"),
      Arguments.of("broken JSON", "{\"orderId\": "),
      Arguments.of("no body at all", ""));
  }

  @ParameterizedTest(name = "{0} is a 400 and nothing happens")
  @MethodSource("invalidBodies")
  void anInvalidBodyIsA400ThatNeverReachesTheRandomNumberService(String name, String body)
    throws Exception {
    RandomApiStub.answersWith(2);

    mockMvc.perform(json(post("/api/payments")
        .header(HttpHeaders.AUTHORIZATION, bearer(anna())), body))
      .andExpectAll(status().isBadRequest(), problemJson());

    assertAll(
      () -> assertEquals(0, RandomApiStub.requestCount()),
      () -> assertEquals(0, storedPayments()));
  }

  @Test
  void aBodyThatIsNotJsonIsA415ThatNeverReachesTheRandomNumberService() throws Exception {
    RandomApiStub.answersWith(2);

    mockMvc.perform(post("/api/payments")
        .header(HttpHeaders.AUTHORIZATION, bearer(anna()))
        .contentType(MediaType.TEXT_PLAIN)
        .content("orderId=5"))
      .andExpectAll(status().isUnsupportedMediaType(), problemJson());

    assertAll(
      () -> assertEquals(0, RandomApiStub.requestCount()),
      () -> assertEquals(0, storedPayments()));
  }

  /** The payer, the outcome, the time and the id are not the client's to choose. */
  @Test
  void fieldsThatTheClientAddsToTheBodyAreIgnored() throws Exception {
    RandomApiStub.answersWith(3);
    String body = """
      {"orderId": 701600, "amount": 24.00, "userId": 99, "status": "SUCCESS", "id": "x",
       "timestamp": "2020-01-01T00:00:00Z"}""";

    mockMvc.perform(json(post("/api/payments")
        .header(HttpHeaders.AUTHORIZATION, bearer(anna())), body))
      .andExpectAll(
        status().isCreated(),
        jsonPath("$.userId").value(ANNA),
        jsonPath("$.status").value("FAILED"),
        jsonPath("$.timestamp").value("2026-11-19T10:00:00Z"));
  }

  @Test
  void anAdminWithAProfilePaysAsHimself() throws Exception {
    RandomApiStub.answersWith(2);

    mockMvc.perform(pay(boss(), 701_700, "24.00")).andExpectAll(
      status().isCreated(),
      jsonPath("$.userId").value(BOSS),
      jsonPath("$.status").value("SUCCESS"));
  }

  /** The whole story of two payments as the payer and the admin read it back. */
  @Test
  void whatWasPaidIsReadBackAndTotalledForThePayerAndForTheAdmin() throws Exception {
    RandomApiStub.answersInOrder(0, 2, 3);
    MvcResult paid = mockMvc.perform(pay(anna(), 701_801, "24.00"))
      .andExpect(status().isCreated()).andReturn();
    mockMvc.perform(pay(anna(), 701_802, "10.50"))
      .andExpectAll(status().isCreated(), jsonPath("$.status").value("FAILED"));
    String id = idOf(paid);
    String period = "from=2026-11-19T00:00:00Z&before=2026-11-20T00:00:00Z";

    mockMvc.perform(get(String.format("/api/payments/%s", id))
        .header(HttpHeaders.AUTHORIZATION, bearer(anna())))
      .andExpectAll(status().isOk(), jsonPath("$.status").value("SUCCESS"));
    mockMvc.perform(get(String.format("/api/payments/%s", id))
        .header(HttpHeaders.AUTHORIZATION, bearer(boris())))
      .andExpectAll(status().isNotFound(), jsonPath("$.detail").value("Payment not found"));
    mockMvc.perform(get("/api/payments/my").header(HttpHeaders.AUTHORIZATION, bearer(anna())))
      .andExpectAll(status().isOk(), jsonPath("$.totalElements").value(2));
    mockMvc.perform(get("/api/payments/my").header(HttpHeaders.AUTHORIZATION, bearer(boris())))
      .andExpectAll(status().isOk(), jsonPath("$.totalElements").value(0));
    mockMvc.perform(get(String.format("/api/payments/my/total?%s", period))
        .header(HttpHeaders.AUTHORIZATION, bearer(anna())))
      .andExpectAll(status().isOk(), jsonPath("$.total").value(24.00),
        jsonPath("$.count").value(1));
    mockMvc.perform(get(String.format("/api/payments/total?%s", period)))
      .andExpectAll(status().isOk(), jsonPath("$.total").value(24.00),
        jsonPath("$.count").value(1));
  }
}
