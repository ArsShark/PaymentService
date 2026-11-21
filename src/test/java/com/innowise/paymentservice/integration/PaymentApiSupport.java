package com.innowise.paymentservice.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.innowise.paymentservice.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Helpers of the API tests that go from the HTTP request to the database and to Kafka: the
 * callers (their tokens) and the builders of requests and readers of answers. Static and without
 * a context of its own, so it cannot change the shared one; it lives next to the tests and is
 * not a second base class.
 *
 * <p>The tokens live ten years, like the default admin token, so a test that moves the clock
 * ahead keeps its caller.
 */
final class PaymentApiSupport {

  /** The profile id of Anna, a user. */
  static final long ANNA = 7;

  /** The profile id of Boris, another user. */
  static final long BORIS = 8;

  /** The profile id of the admin of {@link #boss()}. */
  static final long BOSS = 1;

  private static final Instant FAR = TestTokens.ISSUED_AT.plus(Duration.ofDays(3650));

  private PaymentApiSupport() {
  }

  /** The token of Anna: role USER, profile 7. */
  static String anna() {
    return TestTokens.access().userId(ANNA).email("anna@example.com").expiresAt(FAR).build();
  }

  /** The token of Boris: role USER, profile 8. */
  static String boris() {
    return TestTokens.access().subject("3").userId(BORIS).email("boris@example.com")
      .expiresAt(FAR).build();
  }

  /** The token of an admin that has a profile (1), so he can pay as himself. */
  static String boss() {
    return TestTokens.admin().userId(BOSS).email("boss@example.com").expiresAt(FAR).build();
  }

  /** The JSON of a payment request. The amount is written as it is, for example "24.00". */
  static String paymentBody(long orderId, String amount) {
    return String.format("{\"orderId\": %d, \"amount\": %s}", orderId, amount);
  }

  /** A request that pays an order as the owner of the token. */
  static MockHttpServletRequestBuilder pay(String token, long orderId, String amount) {
    return json(post("/api/payments").header(HttpHeaders.AUTHORIZATION,
      AbstractIntegrationTest.bearer(token)), paymentBody(orderId, amount));
  }

  /** Gives a request a JSON body. */
  static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
    return request.contentType(MediaType.APPLICATION_JSON).content(body);
  }

  /** The text of the body of an answer. */
  static String bodyOf(MvcResult result) throws Exception {
    return bodyOf(result.getResponse());
  }

  /** The text of the body of an answer. */
  static String bodyOf(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }

  /** The {@code id} of the payment in the answer to a payment. */
  static String idOf(MvcResult result) throws Exception {
    return JsonPath.read(bodyOf(result), "$.id");
  }

  /** The {@code detail} of an error answer. */
  static String detailOf(MockHttpServletResponse response) throws Exception {
    return JsonPath.read(bodyOf(response), "$.detail");
  }
}
