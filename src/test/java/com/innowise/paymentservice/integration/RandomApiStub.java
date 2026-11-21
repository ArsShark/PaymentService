package com.innowise.paymentservice.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;

/**
 * The random number service of the integration tests: one WireMock server on a free port, shared
 * by every test like the Spring context. Its address is given to the context as
 * {@code app.random-api.base-uri} by {@link AbstractIntegrationTest}, so no test can reach the
 * real service on the internet by accident.
 *
 * <p>Nothing is stubbed after {@link #reset()}, and WireMock answers 404 to a request it does not
 * know, which the application treats as a service that cannot help (a 502). So a test that
 * forgets to stub the number fails visibly instead of paying with a number nobody chose.
 *
 * <p>The answers look like the real ones: plain text, the number and a line break.
 */
final class RandomApiStub {

  /** The path of the request of {@code RandomNumberClient}; the query is not matched. */
  static final String PATH = "/integers/";

  private static final String SCENARIO = "numbers";

  /** The started server. It has more threads than the default: a delayed answer holds one. */
  private static final WireMockServer SERVER = start();

  private RandomApiStub() {
  }

  private static WireMockServer start() {
    WireMockServer server = new WireMockServer(
      WireMockConfiguration.options().dynamicPort().containerThreads(30));
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    return server;
  }

  /** The address for {@code app.random-api.base-uri}. */
  static String baseUri() {
    return SERVER.baseUrl();
  }

  /** Forgets every stub, every scenario and every recorded request. */
  static void reset() {
    SERVER.resetAll();
  }

  /** Every request is answered with this number. */
  static void answersWith(long number) {
    answersAfter(number, 0);
  }

  /** Every request is answered with this number after this many milliseconds. */
  static void answersAfter(long number, int delayMillis) {
    SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(numberAnswer(number, delayMillis)));
  }

  /** Every request is answered with this status and an empty body. */
  static void answersWithStatus(int status) {
    SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(status)));
  }

  /** Every request is answered with a 200 and this text instead of a number. */
  static void answersWithText(String text) {
    SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse()
      .withStatus(200)
      .withHeader("Content-Type", "text/plain;charset=UTF-8")
      .withBody(text)));
  }

  /** The connection breaks as soon as the request is read. No test waits for a timeout. */
  static void answersWithFault(Fault fault) {
    SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withFault(fault)));
  }

  /**
   * The requests are answered with these numbers one after the other, each after the delay, and
   * the last number repeats. It is a WireMock scenario, so use it ONLY for requests that come
   * one by one: WireMock does not make "match and change the state" atomic, and a request that
   * comes at the same moment as another may find no stub at all and get a 404 (seen in the
   * first run of the parallel test, where it became a 502). For simultaneous requests use
   * {@link #answersWithRandomNumbers(int)}.
   */
  static void answersInOrder(int delayMillis, long... numbers) {
    String state = Scenario.STARTED;
    for (int i = 0; i < numbers.length; i++) {
      boolean last = i == numbers.length - 1;
      String next = last ? state : String.format("number-%d", i + 1);
      SERVER.stubFor(get(urlPathEqualTo(PATH))
        .inScenario(SCENARIO)
        .whenScenarioStateIs(state)
        .willSetStateTo(next)
        .willReturn(numberAnswer(numbers[i], delayMillis)));
      state = next;
    }
  }

  /**
   * Every request is answered with a new random number of three digits after the delay. There is
   * no state, so it is safe for requests that come at the same moment; which parity a request
   * gets is not defined, so a test can only check what holds whatever the numbers are. The
   * numbers are made by the response templating of WireMock ({@code randomValue}).
   */
  static void answersWithRandomNumbers(int delayMillis) {
    SERVER.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse()
      .withStatus(200)
      .withHeader("Content-Type", "text/plain;charset=UTF-8")
      .withBody("{{randomValue length=3 type='NUMERIC'}}\n")
      .withTransformers("response-template")
      .withFixedDelay(delayMillis)));
  }

  /** The requests received so far, oldest first. */
  static List<LoggedRequest> requests() {
    return SERVER.findAll(getRequestedFor(urlPathEqualTo(PATH)));
  }

  /** How many requests the service has received since the last {@link #reset()}. */
  static int requestCount() {
    return requests().size();
  }

  private static ResponseDefinitionBuilder numberAnswer(long number, int delayMillis) {
    return aResponse()
      .withStatus(200)
      .withHeader("Content-Type", "text/plain;charset=UTF-8")
      .withBody(String.format("%d\n", number))
      .withFixedDelay(delayMillis);
  }
}
