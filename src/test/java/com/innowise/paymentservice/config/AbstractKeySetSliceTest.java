package com.innowise.paymentservice.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.innowise.paymentservice.TestTokens;
import com.innowise.paymentservice.controller.PaymentController;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.service.Caller;
import com.innowise.paymentservice.service.PaymentService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base of the tests that put the key set of the Authentication Service in a state of the test's
 * choice (not running, running and then stopped) and send real tokens through the whole security
 * chain: the real decoder, the real chain and the real error handler, with the controller and the
 * service behind them as mocks.
 *
 * <p>Each subclass sets {@code app.security.jwks-uri} with a {@code @DynamicPropertySource}
 * method, so each gets its own context and its own cache of the key. The clock stands one minute
 * after the {@code iat} of the tokens of {@link TestTokens}, so a default token is valid.
 *
 * <p>The request is {@code GET /api/payments/<id>}: any caller with a role may call it, and the
 * service is a mock, so no database is needed.
 */
@WebMvcTest(controllers = PaymentController.class,
  excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, AbstractKeySetSliceTest.FixedClock.class})
abstract class AbstractKeySetSliceTest {

  protected static final String AUTHENTICATION_REQUIRED = "Authentication is required";

  private static final String PAYMENT_ID = "65f0c0ffee0123456789abcd";

  @Autowired
  protected MockMvc mockMvc;

  @MockitoBean
  private PaymentService paymentService;

  @BeforeEach
  void thePaymentExists() {
    Instant now = TestTokens.ISSUED_AT;
    when(paymentService.getById(eq(PAYMENT_ID), any(Caller.class))).thenReturn(
      new PaymentResponse(PAYMENT_ID, 5L, 7L, PaymentStatus.SUCCESS, now,
        new BigDecimal("24.00")));
  }

  /**
   * Sends the request with a token.
   *
   * @param token a compact serialized token
   * @return the result of the request
   */
  protected ResultActions getPaymentWith(String token) throws Exception {
    return mockMvc.perform(get("/api/payments/{id}", PAYMENT_ID)
      .header(HttpHeaders.AUTHORIZATION, String.format("Bearer %s", token)));
  }

  /**
   * Sends the request without any token.
   *
   * @return the result of the request
   */
  protected ResultActions getPaymentWithoutToken() throws Exception {
    return mockMvc.perform(get("/api/payments/{id}", PAYMENT_ID));
  }

  /**
   * Reads the body of an answer as text.
   *
   * @param actions the result of a request
   * @return the body
   */
  protected static String bodyOf(ResultActions actions) throws Exception {
    return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  /** The clock of the slice: one minute after the {@code iat} of the test tokens. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClock {

    @Bean
    Clock clock() {
      return Clock.fixed(TestTokens.ISSUED_AT.plusSeconds(60), ZoneOffset.UTC);
    }
  }
}
