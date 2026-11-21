package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentApiSupport.anna;
import static com.innowise.paymentservice.integration.PaymentApiSupport.bodyOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.boris;
import static com.innowise.paymentservice.integration.PaymentApiSupport.detailOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.pay;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.Document;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * An order paid by several requests at the same moment: the unique index of the successful
 * payments decides, and the answers, the database and the topic agree about the result.
 *
 * <p>Every request is a call on its own thread, and all threads are released by a barrier at
 * the same moment. A race is a matter of luck, so each test is repeated; a correct service
 * passes every repetition, because only what holds whichever way the requests interleave is
 * checked. Eight requests are in flight at most. Every repetition pays an order of its own,
 * because the topic of the events is shared and never emptied.
 */
class PaymentConcurrencyIntegrationTest extends AbstractIntegrationTest {

  private static final int REQUESTS = 8;
  private static final int CREATED = 201;
  private static final int CONFLICT = 409;

  /**
   * Every number is even, so every request that gets as far as the number would pay the order,
   * and the answer is delayed so that all requests have passed the check "is the order already
   * paid" before the first one is saved. Then the unique index is the only thing that stands
   * between the requests and eight successful payments of one order. Exactly one request wins
   * whichever way they interleave: a request that comes late sees the payment and is refused
   * by the check, the others lose at the index, and both give the same 409.
   */
  @RepeatedTest(5)
  void eightRequestsToPayOneOrderGiveOneSuccessAndSevenRefusals(RepetitionInfo info)
    throws Exception {
    long orderId = 707_000 + info.getCurrentRepetition();
    RandomApiStub.answersAfter(2, 400);

    List<MockHttpServletResponse> answers = runTogether(payersOf(orderId));

    List<Integer> statuses = answers.stream().map(MockHttpServletResponse::getStatus).toList();
    long created = statuses.stream().filter(status -> status == CREATED).count();
    long refused = statuses.stream().filter(status -> status == CONFLICT).count();
    List<MockHttpServletResponse> losers = answers.stream()
      .filter(answer -> answer.getStatus() == CONFLICT).toList();
    List<ConsumerRecord<String, String>> events = publishedEventsOf(orderId);
    String eventStatus = events.isEmpty() ? "no event"
      : JsonPath.read(events.get(0).value(), "$.status");
    assertAll(
      () -> assertEquals(1, created, String.format("statuses: %s", statuses)),
      () -> assertEquals(REQUESTS - 1, refused, String.format("statuses: %s", statuses)),
      () -> assertEquals(1, documentsOf(orderId, null), "one document, whoever won"),
      () -> assertEquals(1, documentsOf(orderId, "SUCCESS")),
      () -> assertEquals(1, events.size(), "one event for the one payment"),
      () -> assertEquals("SUCCESS", eventStatus),
      () -> assertTrue(losers.stream().allMatch(PaymentConcurrencyIntegrationTest::isTheRefusal),
        "every refusal says the order is paid"));
  }

  /**
   * The numbers are random, so the mix of even and odd is not defined. (A WireMock scenario
   * cannot hand out numbers to simultaneous requests: some of them would get no answer at all.)
   * So only what holds whichever way it falls is checked: no error, at most one successful
   * payment, an answer for every stored payment and an event for every stored payment.
   */
  @RepeatedTest(5)
  void parallelPaymentsWithMixedNumbersNeverMakeMoreThanOneSuccess(RepetitionInfo info)
    throws Exception {
    long orderId = 707_100 + info.getCurrentRepetition();
    RandomApiStub.answersWithRandomNumbers(200);

    List<MockHttpServletResponse> answers = runTogether(payersOf(orderId));

    List<Integer> statuses = answers.stream().map(MockHttpServletResponse::getStatus).toList();
    long created = statuses.stream().filter(status -> status == CREATED).count();
    long successes = documentsOf(orderId, "SUCCESS");
    long successAnswers = answers.stream()
      .filter(answer -> answer.getStatus() == CREATED && isSuccess(answer)).count();
    List<ConsumerRecord<String, String>> events = publishedEventsOf(orderId);
    assertAll(
      () -> assertTrue(statuses.stream().allMatch(status -> status == CREATED
          || status == CONFLICT), String.format("only 201 and 409 are allowed: %s", statuses)),
      () -> assertTrue(successes <= 1, "an order is paid at most once"),
      () -> assertEquals(successes, successAnswers, "the answers tell what the database holds"),
      () -> assertEquals(created, documentsOf(orderId, null),
        "every 201 is one stored payment and every stored payment was answered with a 201"),
      () -> assertEquals(created, events.size(), "an event for every stored payment"));
  }

  /** Eight payers: Anna and Boris take turns, so different users race for the same order. */
  private List<Callable<MockHttpServletResponse>> payersOf(long orderId) {
    List<Callable<MockHttpServletResponse>> tasks = new ArrayList<>();
    for (int i = 0; i < REQUESTS; i++) {
      String token = i % 2 == 0 ? anna() : boris();
      tasks.add(answerTo(pay(token, orderId, "24.00")));
    }
    return tasks;
  }

  private Callable<MockHttpServletResponse> answerTo(MockHttpServletRequestBuilder request) {
    return () -> mockMvc.perform(request).andReturn().getResponse();
  }

  /** Runs the tasks on separate threads, starts them at the same moment and collects answers. */
  private static List<MockHttpServletResponse> runTogether(
    List<Callable<MockHttpServletResponse>> tasks) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
    CyclicBarrier barrier = new CyclicBarrier(tasks.size());
    try {
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (Callable<MockHttpServletResponse> task : tasks) {
        futures.add(executor.submit(() -> {
          barrier.await(30, TimeUnit.SECONDS);
          return task.call();
        }));
      }
      List<MockHttpServletResponse> answers = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        answers.add(future.get(60, TimeUnit.SECONDS));
      }
      return answers;
    } finally {
      executor.shutdownNow();
    }
  }

  /** How many payments of the order are stored; with a status only those, otherwise all. */
  private long documentsOf(long orderId, String status) {
    Document filter = new Document("order_id", orderId);
    if (status != null) {
      filter.append("status", status);
    }
    return mongoTemplate.getCollection(PAYMENTS).countDocuments(filter);
  }

  private static boolean isTheRefusal(MockHttpServletResponse answer) {
    try {
      return detailOf(answer).endsWith(" is already paid");
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isSuccess(MockHttpServletResponse answer) {
    try {
      return "SUCCESS".equals(JsonPath.read(bodyOf(answer), "$.status"));
    } catch (Exception e) {
      return false;
    }
  }
}
