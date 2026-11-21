package com.innowise.paymentservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.innowise.paymentservice.client.RandomNumberClient;
import com.innowise.paymentservice.exception.RandomNumberUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Tests of {@link RandomApiStub} itself, and of the way the application is wired to it: a helper
 * that every API test relies on must be proved before they rely on it.
 *
 * <p>The client of the application is the real one, so these tests also prove that the shared
 * context talks to the stub and never to the real random number service.
 */
class RandomApiStubIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private RandomNumberClient client;

  @Value("${app.random-api.base-uri}")
  private String configuredBaseUri;

  @Test
  void theApplicationIsPointedAtTheStub() {
    assertEquals(RandomApiStub.baseUri(), configuredBaseUri);
  }

  @Test
  void theClientOfTheApplicationGetsTheStubbedNumberAndSendsTheRequestOfTheContract() {
    RandomApiStub.answersWith(42);

    long number = client.nextNumber();

    List<LoggedRequest> requests = RandomApiStub.requests();
    String query = requests.get(0).getQueryParams().values().stream()
      .map(value -> String.format("%s=%s", value.key(), value.firstValue()))
      .sorted()
      .toList()
      .toString();
    assertAll(
      () -> assertEquals(42, number),
      () -> assertEquals(1, requests.size()),
      () -> assertEquals(
        "[base=10, col=1, format=plain, max=100, min=1, num=1, rnd=new]", query));
  }

  @Test
  void aRequestThatNothingWasStubbedForIsAFailureOfTheServiceNotANumber() {
    assertThrows(RandomNumberUnavailableException.class, client::nextNumber);
    assertEquals(1, RandomApiStub.requestCount());
  }

  @Test
  void resetForgetsTheStubsAndTheRequests() {
    RandomApiStub.answersWith(8);
    client.nextNumber();

    RandomApiStub.reset();

    assertAll(
      () -> assertEquals(0, RandomApiStub.requestCount()),
      () -> assertThrows(RandomNumberUnavailableException.class, client::nextNumber));
  }

  @Test
  void numbersInOrderComeOneByOneAndTheLastOneRepeats() {
    RandomApiStub.answersInOrder(0, 2, 3, 4);

    List<Long> numbers = List.of(client.nextNumber(), client.nextNumber(), client.nextNumber(),
      client.nextNumber(), client.nextNumber());

    assertEquals(List.of(2L, 3L, 4L, 4L, 4L), numbers);
  }

  @Test
  void aSingleNumberInOrderRepeatsToo() {
    RandomApiStub.answersInOrder(0, 7);

    assertEquals(List.of(7L, 7L), List.of(client.nextNumber(), client.nextNumber()));
  }

  /** The numbers of a stub without state: always numbers, and a mix of even and odd. */
  @Test
  void randomNumbersAreNumbersOfBothParities() {
    RandomApiStub.answersWithRandomNumbers(0);

    List<Long> numbers = IntStream.range(0, 40).mapToObj(i -> client.nextNumber()).toList();

    assertAll(
      () -> assertEquals(40, RandomApiStub.requestCount()),
      () -> assertTrue(numbers.stream().anyMatch(number -> number % 2 == 0), "no even number"),
      () -> assertTrue(numbers.stream().anyMatch(number -> number % 2 != 0), "no odd number"));
  }

  /**
   * Simultaneous requests all get an answer: this is what a scenario could not do. The requests
   * come from the common pool, so they overlap.
   */
  @Test
  void simultaneousRequestsAllGetANumber() {
    RandomApiStub.answersWithRandomNumbers(100);

    List<Long> numbers = IntStream.range(0, 32).parallel()
      .mapToObj(i -> client.nextNumber()).toList();

    assertAll(
      () -> assertEquals(32, numbers.size()),
      () -> assertEquals(32, RandomApiStub.requestCount()));
  }

  @Test
  void aDelayedAnswerTakesAtLeastTheDelay() {
    RandomApiStub.answersAfter(6, 300);

    long startedAt = System.nanoTime();
    long number = client.nextNumber();
    Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

    assertAll(
      () -> assertEquals(6, number),
      () -> assertTrue(took.toMillis() >= 300, String.format("took only %s", took)));
  }

  @Test
  void anErrorStatusATextAndABrokenConnectionAreAllFailuresOfTheService() {
    RandomApiStub.answersWithStatus(503);
    RandomNumberUnavailableException status =
      assertThrows(RandomNumberUnavailableException.class, client::nextNumber);

    RandomApiStub.answersWithText("Error: quota is used up");
    RandomNumberUnavailableException text =
      assertThrows(RandomNumberUnavailableException.class, client::nextNumber);

    RandomApiStub.answersWithFault(Fault.CONNECTION_RESET_BY_PEER);
    RandomNumberUnavailableException fault =
      assertThrows(RandomNumberUnavailableException.class, client::nextNumber);

    assertAll(
      () -> assertEquals(RandomNumberUnavailableException.MESSAGE, status.getMessage()),
      () -> assertEquals(RandomNumberUnavailableException.MESSAGE, text.getMessage()),
      () -> assertEquals(RandomNumberUnavailableException.MESSAGE, fault.getMessage()));
  }
}
