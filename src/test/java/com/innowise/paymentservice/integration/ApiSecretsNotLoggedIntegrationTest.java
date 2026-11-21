package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentApiSupport.idOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.json;
import static com.innowise.paymentservice.integration.PaymentApiSupport.pay;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.http.Fault;
import com.innowise.paymentservice.LogCapture;
import com.innowise.paymentservice.TestKeys;
import com.innowise.paymentservice.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Log hygiene through the REST API: one run sends real requests with real tokens, successes and
 * every kind of refusal (401, 403, 404, 409, 400, 415), and walks through the troubles of the
 * random number service (an error status, a broken connection, no answer in time). Then every
 * secret of the run is searched for in everything that was logged: the tokens (whole, and their
 * payload and signature parts), the claim {@code jti}, the emails, the addresses of the key set
 * and of the random number service, the password of the database, the text of a duplicate key
 * error of MongoDB, and the values that the refused requests carried.
 *
 * <p>The run must also have written the lines that are expected, so a capture that sees nothing
 * cannot pass. {@link LogCapture} sees the lines of every library, not only of this service.
 *
 * <p>The run takes as long as the read timeout of the random number service (5 s): it is the
 * only way to see the line of a timeout.
 */
class ApiSecretsNotLoggedIntegrationTest extends AbstractIntegrationTest {

  private static final String ANNA_EMAIL = "zinaida.quarterman@example.com";
  private static final String BORIS_EMAIL = "boris.secret@example.com";
  private static final String BOSS_EMAIL = "boss.secret@example.com";
  private static final String PERIOD =
    "from=2026-11-19T00:00:00Z&before=2026-11-20T00:00:00Z";

  @Autowired
  private WebApplicationContext webContext;

  @Value("${app.security.jwks-uri}")
  private String keySetAddress;

  @Value("${spring.mongodb.password}")
  private String databasePassword;

  @Test
  void noTokenNoEmailNoAddressNoPasswordAndNoValueOfARefusedRequestReachesAnyLogLine()
    throws Exception {
    String anna = token(TestTokens.access().userId(7L).email(ANNA_EMAIL));
    String boris = token(TestTokens.access().subject("3").userId(8L).email(BORIS_EMAIL));
    String boss = token(TestTokens.admin().userId(1L).email(BOSS_EMAIL));
    String expired = TestTokens.admin()
      .issuedAt(START.minus(TestTokens.ACCESS_TTL))
      .expiresAt(START.minusSeconds(1))
      .build();
    String anotherKey = TestTokens.admin().signedWith(TestKeys.generateRsa(2048)).build();
    String garbage = "secret-garbage-token-value";

    Map<String, String> secrets = new LinkedHashMap<>();
    addToken(secrets, "the token of Anna", anna);
    addToken(secrets, "the token of Boris", boris);
    addToken(secrets, "the token of the admin", boss);
    addToken(secrets, "the expired token", expired);
    addToken(secrets, "the token of another key", anotherKey);
    secrets.put("the garbage token", garbage);
    secrets.put("the email of Anna", ANNA_EMAIL);
    secrets.put("the email of Boris", BORIS_EMAIL);
    secrets.put("the email of the admin", BOSS_EMAIL);
    secrets.put("the address of the key set", keySetAddress);
    secrets.put("the address of the random number service", RandomApiStub.baseUri());
    secrets.put("the request to the random number service", RandomApiStub.PATH);
    secrets.put("the password of the database", databasePassword);
    secrets.put("the code of a duplicate key error", "E11000");
    secrets.put("the text of a duplicate key error", "dup key");
    secrets.put("a status of a request", "secret-status-value");
    secrets.put("a date of a request", "secret-date-value");
    secrets.put("a sort property of a request", "secret-sort-property");
    secrets.put("a path value", "secretpathid");
    secrets.put("a body text", "secret-body-text");

    MockMvc anonymous = MockMvcBuilders.webAppContextSetup(webContext)
      .apply(springSecurity()).build();

    try (LogCapture capture = LogCapture.attach()) {
      // Successes: a paid and a failed payment of two users, an admin who pays as himself, and
      // every kind of reading.
      RandomApiStub.answersWith(2);
      MvcResult paid = mockMvc.perform(pay(anna, 708_001, "24.00"))
        .andExpect(status().isCreated()).andReturn();
      RandomApiStub.answersWith(3);
      mockMvc.perform(pay(boris, 708_002, "10.50")).andExpect(status().isCreated());
      RandomApiStub.answersWith(2);
      mockMvc.perform(pay(boss, 708_003, "5.00")).andExpect(status().isCreated());
      String id = idOf(paid);
      mockMvc.perform(as(get("/api/payments/{id}", id), anna)).andExpect(status().isOk());
      mockMvc.perform(as(get("/api/payments/my"), anna)).andExpect(status().isOk());
      mockMvc.perform(get("/api/payments")).andExpect(status().isOk());
      mockMvc.perform(as(get(String.format("/api/payments/my/total?%s", PERIOD)), anna))
        .andExpect(status().isOk());
      mockMvc.perform(get(String.format("/api/payments/total?%s", PERIOD)))
        .andExpect(status().isOk());

      // Refusals: 404 (a foreign payment and an id that is no id), 409, 403, and bad requests.
      mockMvc.perform(as(get("/api/payments/{id}", id), boris))
        .andExpect(status().isNotFound());
      mockMvc.perform(as(get("/api/payments/secretpathid"), anna))
        .andExpect(status().isNotFound());
      mockMvc.perform(pay(anna, 708_001, "24.00")).andExpect(status().isConflict());
      mockMvc.perform(as(get("/api/payments"), anna)).andExpect(status().isForbidden());
      mockMvc.perform(pay(ADMIN_TOKEN, 708_004, "24.00")).andExpect(status().isForbidden());
      mockMvc.perform(get("/api/payments").param("status", "secret-status-value"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(get("/api/payments/total")
          .param("from", "secret-date-value").param("before", "2026-11-20T00:00:00Z"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(get("/api/payments").param("sort", "secret-sort-property"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(json(as(post("/api/payments"), anna), "{\"orderId\": [\"secret-body-text\""))
        .andExpect(status().isBadRequest());
      mockMvc.perform(json(as(post("/api/payments"), anna),
          "{\"orderId\": 708005, \"amount\": \"secret-body-text\"}"))
        .andExpect(status().isBadRequest());
      mockMvc.perform(as(post("/api/payments"), anna)
          .contentType(MediaType.TEXT_PLAIN).content("secret-body-text"))
        .andExpect(status().isUnsupportedMediaType());

      // 401: no token and every kind of bad token.
      anonymous.perform(get("/api/payments/my")).andExpect(status().isUnauthorized());
      for (String bad : new String[] {expired, anotherKey, garbage}) {
        anonymous.perform(as(get("/api/payments/my"), bad)).andExpect(status().isUnauthorized());
      }

      // The troubles of the random number service: an error status, a broken connection and an
      // answer that does not come in time.
      RandomApiStub.answersWithStatus(503);
      mockMvc.perform(pay(anna, 708_006, "24.00")).andExpect(status().isBadGateway());
      RandomApiStub.answersWithFault(Fault.CONNECTION_RESET_BY_PEER);
      mockMvc.perform(pay(anna, 708_007, "24.00")).andExpect(status().isBadGateway());
      RandomApiStub.answersAfter(2, 8000);
      mockMvc.perform(pay(anna, 708_008, "24.00")).andExpect(status().isGatewayTimeout());

      // The events are sent in the background: let every send finish, then look at the log.
      kafkaTemplate.flush();

      assertAll(
        () -> assertFalse(capture.lines().isEmpty(), "the run must have logged something"),
        () -> assertTrue(capture.contains("Created payment")),
        () -> assertTrue(capture.contains("Published CREATE_PAYMENT of payment")),
        () -> assertTrue(capture.contains("Payment refused: order 708001 is already paid")),
        () -> assertTrue(capture.contains("Request rejected with status 404")),
        () -> assertTrue(capture.contains("Request rejected with status 409")),
        () -> assertTrue(capture.contains("Request rejected with status 403")),
        () -> assertTrue(capture.contains("Request rejected with status 401")),
        () -> assertTrue(capture.contains("The random number service answered with status 503")),
        () -> assertTrue(capture.contains("The call to the random number service failed (")),
        () -> assertTrue(capture.contains("The call to the random number service ran out of")),
        () -> assertTrue(capture.contains("Request failed with status 502")),
        () -> assertTrue(capture.contains("Request failed with status 504")));
      secrets.forEach((name, secret) -> assertFalse(capture.contains(secret),
        String.format("%s must not be logged", name)));
    }
  }

  private static String token(TestTokens.Builder builder) {
    return builder.expiresAt(START.plus(Duration.ofDays(3650))).build();
  }

  private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,
    String token) {
    return request.header(HttpHeaders.AUTHORIZATION, bearer(token));
  }

  /**
   * Adds a token as a whole, its payload and its signature (the parts that make it unique), and
   * its {@code jti} when it has one.
   */
  private static void addToken(Map<String, String> secrets, String name, String token) {
    String[] parts = token.split("\\.");
    secrets.put(name, token);
    secrets.put(String.format("the signature of %s", name), parts[2]);
    String payload = parts[1];
    secrets.put(String.format("the payload of %s", name), payload);
    String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
    Map<String, Object> claims = JsonPath.read(json, "$");
    Object jti = claims.get("jti");
    if (jti != null) {
      secrets.put(String.format("the jti of %s", name), jti.toString());
    }
  }
}
