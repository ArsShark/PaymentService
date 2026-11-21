package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentApiSupport.anna;
import static com.innowise.paymentservice.integration.PaymentApiSupport.idOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.pay;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.paymentservice.LogCapture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.kafka.KafkaContainer;

/**
 * A payment while the broker does not answer: the payment is saved and answered, the HTTP thread
 * is not held, and the event that could not be sent is logged once at ERROR with the ids and the
 * class of the cause, and nothing else.
 *
 * <p>The broker container is paused, not stopped: a paused container keeps its port and its
 * connections and answers nothing, which is how a hung broker looks to a client (a stopped one
 * would come back on another port). With the real settings of the producer (delivery timeout
 * 30 s) the line of the lost event comes about half a minute after the request, so this test
 * takes about that long; it is the price of testing the real settings. A first send in a JVM
 * that has no metadata of the topic yet fails earlier (the limit of 2 s of {@code max.block.ms})
 * with the same line.
 *
 * <p>What is NOT asserted: that the event is absent from the topic. A paused broker keeps the
 * bytes that were sent to it and may handle them after it is resumed (like the paused Redis of
 * the User Service keeps its commands), so the record may appear later. What is proved is the
 * promise of the service: the answer, the document and the line of the log.
 *
 * <p>The test also proves that the producer recovers: the next payment after the resume is on
 * the topic. The container is resumed in {@code finally} and in {@code @AfterEach}, so a failed
 * test never leaves the shared broker paused for the tests of other classes.
 */
class KafkaOutageIntegrationTest extends AbstractIntegrationTest {

  private static final Duration LOST_EVENT_DEADLINE = Duration.ofSeconds(90);
  private static final Duration HTTP_THREAD_LIMIT = Duration.ofSeconds(10);

  /** A regular expression: the ids, the class of the cause (a name that ends in Exception). */
  private static final String LOST_EVENT_SHAPE = String.join(" ",
    "Event of payment %s of order 709002 NOT published",
    "\\(\\w+Exception\\),",
    "the Order Service was not told");

  @Autowired
  private KafkaContainer kafkaContainer;

  /** Makes sure that a failed test never leaves the shared broker paused. */
  @AfterEach
  void resumeKafka() {
    if (isPaused()) {
      unpauseKafka();
    }
  }

  @Test
  void aPaymentIsSavedAndAnsweredWhileKafkaIsPausedAndTheLostEventIsLoggedOnce()
    throws Exception {
    RandomApiStub.answersWith(2);
    // A payment before the outage: the producer knows the broker and the topic, so the send
    // during the outage is a send that is buffered and times out, as in production.
    mockMvc.perform(pay(anna(), 709_001, "24.00")).andExpect(status().isCreated());
    assertEquals(1, publishedEventsOf(709_001).size());

    try (LogCapture capture = LogCapture.attach()) {
      pauseKafka();
      try {
        Instant sentAt = Instant.now();
        MvcResult result = mockMvc.perform(pay(anna(), 709_002, "24.00")).andExpectAll(
          status().isCreated(),
          jsonPath("$.status").value("SUCCESS")).andReturn();
        Duration answeredIn = Duration.between(sentAt, Instant.now());
        String paymentId = idOf(result);

        String line = awaitLine(capture,
          String.format("Event of payment %s of order 709002 NOT published", paymentId));
        List<String> lostEventLines = capture.lines().stream()
          .filter(candidate -> candidate.contains("NOT published")).toList();
        String shape = String.format(LOST_EVENT_SHAPE, paymentId);
        assertAll(
          () -> assertTrue(answeredIn.compareTo(HTTP_THREAD_LIMIT) < 0,
            String.format("the HTTP thread was held for %s", answeredIn)),
          () -> assertEquals(1, mongoTemplate.getCollection(PAYMENTS)
            .countDocuments(new Document("order_id", 709_002L).append("status", "SUCCESS"))),
          () -> assertTrue(line.strip().matches(shape),
            "the line has the ids and the class of the cause and no message"),
          () -> assertEquals(1, lostEventLines.size(), "one line for the one lost event"),
          () -> assertFalse(line.contains(kafkaBootstrapServers),
            "the address of the broker must not be in the line"));
      } finally {
        unpauseKafka();
      }
    }

    // The producer works again: the next payment is on the topic.
    mockMvc.perform(pay(anna(), 709_003, "24.00")).andExpect(status().isCreated());
    assertEquals(1, publishedEventsOf(709_003).size());
  }

  /** Waits for the line of the log (the delivery timeout of the producer is what takes time). */
  private static String awaitLine(LogCapture capture, String part) throws InterruptedException {
    Instant giveUpAt = Instant.now().plus(LOST_EVENT_DEADLINE);
    while (Instant.now().isBefore(giveUpAt)) {
      for (String line : capture.lines()) {
        if (line.contains(part)) {
          return line;
        }
      }
      Thread.sleep(200);
    }
    return fail(String.format("no line with '%s' in %s", part, LOST_EVENT_DEADLINE));
  }

  private void pauseKafka() {
    kafkaContainer.getDockerClient().pauseContainerCmd(kafkaContainer.getContainerId()).exec();
  }

  private void unpauseKafka() {
    if (isPaused()) {
      kafkaContainer.getDockerClient().unpauseContainerCmd(kafkaContainer.getContainerId())
        .exec();
    }
  }

  private boolean isPaused() {
    return Boolean.TRUE.equals(kafkaContainer.getDockerClient()
      .inspectContainerCmd(kafkaContainer.getContainerId())
      .exec()
      .getState()
      .getPaused());
  }
}
