package com.innowise.paymentservice.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.innowise.paymentservice.config.RandomApiClientConfig;
import com.innowise.paymentservice.config.RandomApiProperties;
import com.innowise.paymentservice.exception.RandomNumberTimeoutException;
import com.innowise.paymentservice.exception.RandomNumberUnavailableException;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

/**
 * Tests of {@link RandomNumberClient} against a real HTTP server (WireMock): what goes out on the
 * wire and how every kind of answer is classified.
 *
 * <p>One server serves the whole class and is reset before every test. The client of
 * {@link #setUp()} has a generous read timeout, so a slow first request of a cold server cannot
 * end a test; a short timeout is used only in the one test that proves the timeout, and there
 * the stub delay is ten times longer. No test sleeps.
 */
class RandomNumberClientTest {

  private static final String PATH = "/integers/";

  @RegisterExtension
  static WireMockExtension randomService = WireMockExtension.newInstance()
    .options(WireMockConfiguration.wireMockConfig().dynamicPort())
    .build();

  private RandomNumberClient client;

  @BeforeEach
  void setUp() {
    client = clientFor(randomService.baseUrl(), Duration.ofSeconds(10));
  }

  private static ResponseDefinitionBuilder plain(String body) {
    return aResponse().withStatus(200)
      .withHeader("Content-Type", "text/plain;charset=UTF-8").withBody(body);
  }

  private static void stubAnswer(ResponseDefinitionBuilder answer) {
    randomService.stubFor(get(urlPathEqualTo(PATH)).willReturn(answer));
  }

  @Test
  void asksForOneIntegerFromOneToOneHundredAsPlainText() {
    stubAnswer(plain("42\n"));

    long number = client.nextNumber();

    assertAll(
      () -> assertEquals(42L, number),
      () -> randomService.verify(1, getRequestedFor(urlPathEqualTo(PATH))
        .withQueryParam("num", equalTo("1"))
        .withQueryParam("min", equalTo("1"))
        .withQueryParam("max", equalTo("100"))
        .withQueryParam("col", equalTo("1"))
        .withQueryParam("base", equalTo("10"))
        .withQueryParam("format", equalTo("plain"))
        .withQueryParam("rnd", equalTo("new"))
        .withHeader("Accept", containing("text/plain"))));
  }

  static Stream<Arguments> usableAnswers() {
    return Stream.of(
      Arguments.of("42", 42L),
      Arguments.of("42\n", 42L),
      Arguments.of("  42  \r\n", 42L),
      Arguments.of("\n7\n", 7L),
      Arguments.of("0\n", 0L),
      Arguments.of("-7\n", -7L),
      Arguments.of("100\n", 100L));
  }

  @ParameterizedTest
  @MethodSource("usableAnswers")
  void anIntegerWithALineBreakAroundItIsTheNumber(String body, long expected) {
    stubAnswer(plain(body));

    assertEquals(expected, client.nextNumber());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\n", "abc", "Error: You have used your quota of random bits",
    "4 2", "42\n43", "3.5", "1e3", "+5", "0x2A", "99999999999999999999"})
  void anAnswerThatIsNotOneIntegerMeansTheServiceCannotHelp(String body) {
    stubAnswer(plain(body));

    RandomNumberUnavailableException thrown =
      assertThrows(RandomNumberUnavailableException.class, () -> client.nextNumber());

    assertEquals(RandomNumberUnavailableException.MESSAGE, thrown.getMessage());
  }

  @Test
  void aSuccessfulAnswerWithoutABodyMeansTheServiceCannotHelp() {
    stubAnswer(aResponse().withStatus(200));

    assertThrows(RandomNumberUnavailableException.class, () -> client.nextNumber());
  }

  /** random.org answers 503 and "Error: ..." in the body when its quota is exhausted. */
  @ParameterizedTest
  @ValueSource(ints = {400, 403, 404, 429, 500, 502, 503})
  void anErrorStatusMeansTheServiceCannotHelp(int status) {
    stubAnswer(aResponse().withStatus(status).withHeader("Content-Type", "text/plain")
      .withBody("Error: 42"));

    RandomNumberUnavailableException thrown =
      assertThrows(RandomNumberUnavailableException.class, () -> client.nextNumber());

    assertFalse(thrown.getMessage().contains("42"));
  }

  @ParameterizedTest
  @ValueSource(ints = {201, 204, 301, 302})
  void aStatusOtherThan200MeansTheServiceCannotHelpEvenWithANumberInTheBody(int status) {
    stubAnswer(aResponse().withStatus(status).withHeader("Location", "http://localhost/other")
      .withHeader("Content-Type", "text/plain").withBody("42\n"));

    assertThrows(RandomNumberUnavailableException.class, () -> client.nextNumber());
  }

  @ParameterizedTest
  @EnumSource(value = Fault.class, names = {"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE"})
  void aBrokenConnectionMeansTheServiceCannotHelp(Fault fault) {
    stubAnswer(aResponse().withFault(fault));

    assertThrows(RandomNumberUnavailableException.class, () -> client.nextNumber());
  }

  @Test
  void aRefusedConnectionMeansTheServiceCannotHelp() throws IOException {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    RandomNumberClient offline = clientFor(
      String.format("http://localhost:%d", closedPort), Duration.ofSeconds(10));

    RandomNumberUnavailableException thrown =
      assertThrows(RandomNumberUnavailableException.class, () -> offline.nextNumber());

    assertFalse(thrown.getMessage().contains("localhost"));
  }

  @Test
  void anAnswerThatTakesTooLongIsATimeout() {
    stubAnswer(plain("42\n").withFixedDelay(5000));
    RandomNumberClient impatient = clientFor(randomService.baseUrl(), Duration.ofMillis(500));

    long started = System.nanoTime();
    RandomNumberTimeoutException thrown =
      assertThrows(RandomNumberTimeoutException.class, () -> impatient.nextNumber());
    Duration waited = Duration.ofNanos(System.nanoTime() - started);

    assertAll(
      () -> assertEquals(RandomNumberTimeoutException.MESSAGE, thrown.getMessage()),
      () -> assertTrue(waited.compareTo(Duration.ofSeconds(4)) < 0,
        String.format("the client waited %s for an answer that took 5 s", waited)));
  }

  @Test
  void theMessagesNameNeitherTheAddressNorTheBody() {
    assertAll(
      () -> assertFalse(RandomNumberUnavailableException.MESSAGE.contains("http")),
      () -> assertFalse(RandomNumberTimeoutException.MESSAGE.contains("http")));
  }

  private static RandomNumberClient clientFor(String baseUri, Duration readTimeout) {
    RandomApiProperties properties =
      new RandomApiProperties(baseUri, Duration.ofSeconds(2), readTimeout);
    RestClient restClient = new RandomApiClientConfig().randomApiRestClient(properties);
    return new RandomNumberClient(restClient);
  }
}
