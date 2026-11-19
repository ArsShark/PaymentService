package com.innowise.paymentservice.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.entity.PaymentStatus;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.stream.Stream;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tests of {@link GlobalExceptionHandler}.
 *
 * <p>The handler is exercised through a standalone MockMvc and a throwaway controller, so no
 * Spring context is started and the tests do not depend on the real controllers.
 */
class GlobalExceptionHandlerTest {

  /** What MongoDB says for a duplicate key: the document of the refused payment is in it. */
  private static final String DUPLICATE_KEY_MESSAGE = String.format(
    "E11000 duplicate key error collection: %s index: %s dup key: %s",
    "payment_service_db.payments", "uq_some_index", "{ order_id: 777555 }");

  private ThrowingController controller;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    controller = new ThrowingController();
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.setMessageInterpolator(new ParameterMessageInterpolator());
    validator.afterPropertiesSet();
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler())
      .setValidator(validator)
      .build();
  }

  static Stream<RuntimeException> badRequestExceptions() {
    return Stream.of(
      new InvalidSortPropertyException(Set.of("id", "status")),
      new InvalidPeriodException());
  }

  @Test
  void mapsAMissingOrForeignPaymentTo404() throws Exception {
    controller.exceptionToThrow = new PaymentNotFoundException();

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isNotFound(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(404),
      jsonPath("$.detail").value("Payment not found"));
  }

  @Test
  void mapsAPaidOrderTo409() throws Exception {
    controller.exceptionToThrow = new OrderAlreadyPaidException(5L);

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isConflict(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(409),
      jsonPath("$.detail").value("Order 5 is already paid"));
  }

  @ParameterizedTest
  @MethodSource("badRequestExceptions")
  void mapsBadRequestsTo400(RuntimeException exception) throws Exception {
    controller.exceptionToThrow = exception;

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      jsonPath("$.detail").value(exception.getMessage()));
  }

  @Test
  void mapsAnUnavailableRandomNumberServiceTo502WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new RandomNumberUnavailableException();

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isBadGateway(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(502),
      jsonPath("$.detail").value("The random number service is not available"));
  }

  @Test
  void mapsASlowRandomNumberServiceTo504WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new RandomNumberTimeoutException();

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isGatewayTimeout(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(504),
      jsonPath("$.detail").value("The random number service did not answer in time"));
  }

  /** The message of MongoDB holds the refused document: it must not reach the client. */
  @Test
  void mapsAFailureOfTheDatabaseTo500WithoutTheDocument() throws Exception {
    controller.exceptionToThrow = new DuplicateKeyException(DUPLICATE_KEY_MESSAGE);

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("777555"))));
  }

  @Test
  void mapsAnUnreachableDatabaseTo500() throws Exception {
    controller.exceptionToThrow = new DataAccessResourceFailureException("cannot connect to host");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("cannot connect"))));
  }

  /** What goes to the log: the classes and the index, never a message. */
  @Test
  void describesAFailureOfTheDatabaseWithClassesAndTheIndexOnly() {
    DuplicateKeyException failure = new DuplicateKeyException(DUPLICATE_KEY_MESSAGE,
      new IllegalStateException("root message with 777555"));

    String description = GlobalExceptionHandler.describe(failure);

    assertAll(
      () -> assertEquals(String.format("%s, root cause %s, index %s",
        "DuplicateKeyException", "IllegalStateException", "uq_some_index"), description),
      () -> assertFalse(description.contains("777555")));
  }

  @Test
  void describesAFailureWithoutAnIndex() {
    String description = GlobalExceptionHandler.describe(
      new DataAccessResourceFailureException("cannot connect"));

    assertTrue(description.endsWith("index null"));
  }

  @Test
  void hidesInternalDetailsOfUnexpectedExceptions() throws Exception {
    controller.exceptionToThrow = new IllegalStateException("secret internal detail");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("secret internal detail"))));
  }

  @Test
  void listsInvalidFieldsWithoutRejectedValues() throws Exception {
    String body = "{\"orderId\": -987654, \"amount\": 0}";

    mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Request validation failed"),
      jsonPath("$.errors.length()").value(2),
      jsonPath("$.errors[0].field").value("amount"),
      jsonPath("$.errors[1].field").value("orderId"),
      content().string(not(containsString("987654"))));
  }

  @Test
  void answersMalformedJsonWith400() throws Exception {
    String body = "{\"orderId\": 55555, \"amount\": ";

    mockMvc.perform(post("/test/validated").contentType(MediaType.APPLICATION_JSON)
      .content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      content().string(not(containsString("55555"))));
  }

  @Test
  void answersWrongPathVariableTypeWith400() throws Exception {
    mockMvc.perform(get("/test/numbers/abc")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400),
      jsonPath("$.detail").value("Invalid value of the parameter 'id'"));
  }

  /** Spring's own detail would repeat the value of the client; ours names only the parameter. */
  @Test
  void doesNotEchoTheValueOfAParameterThatCannotBeConverted() throws Exception {
    mockMvc.perform(get("/test/filter").param("status", "BOGUS-VALUE")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.detail").value("Invalid value of the parameter 'status'"),
      content().string(not(containsString("BOGUS-VALUE"))));
  }

  @Test
  void answersAMissingParameterWith400() throws Exception {
    mockMvc.perform(get("/test/filter")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(400));
  }

  @Test
  void answersUnsupportedMethodWith405() throws Exception {
    mockMvc.perform(post("/test/numbers/1")).andExpectAll(
      status().isMethodNotAllowed(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(405));
  }

  /** The constant detail never says why, and the message of the exception is not sent. */
  @Test
  void mapsAuthenticationFailuresTo401WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new BadCredentialsException("token secret-value expired");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isUnauthorized(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(401),
      jsonPath("$.detail").value("Authentication is required"),
      content().string(not(containsString("secret-value"))));
  }

  /** Without an explicit handler the catch-all would turn this into a 500. */
  @Test
  void mapsAccessDeniedTo403WithAConstantDetail() throws Exception {
    controller.exceptionToThrow = new AccessDeniedException("payment 42 belongs to user 9");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isForbidden(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(403),
      jsonPath("$.detail").value("Access is denied"),
      content().string(not(containsString("payment 42"))));
  }

  /** Controller whose only job is to throw what a test asks for. */
  @RestController
  @RequestMapping("/test")
  static class ThrowingController {

    RuntimeException exceptionToThrow;

    @GetMapping("/throw")
    String throwConfiguredException() {
      throw exceptionToThrow;
    }

    @GetMapping("/numbers/{id}")
    String number(@PathVariable Long id) {
      return "ok";
    }

    @GetMapping("/filter")
    String filter(@RequestParam PaymentStatus status) {
      return "ok";
    }

    @PostMapping("/validated")
    String validated(@Valid @RequestBody PaymentRequest request) {
      return "ok";
    }
  }
}
