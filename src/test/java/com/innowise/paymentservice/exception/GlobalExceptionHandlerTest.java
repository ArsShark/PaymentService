package com.innowise.paymentservice.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tests of {@link GlobalExceptionHandler}.
 *
 * <p>The handler is exercised through a standalone MockMvc and a throwaway controller, so no
 * Spring context is started and the tests do not depend on the real controllers.
 */
class GlobalExceptionHandlerTest {

  private ThrowingController controller;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    controller = new ThrowingController();
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();
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

  @Test
  void hidesInternalDetailsOfUnexpectedExceptions() throws Exception {
    controller.exceptionToThrow = new IllegalStateException("secret internal detail");

    mockMvc.perform(get("/test/throw")).andExpectAll(
      status().isInternalServerError(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(500),
      jsonPath("$.detail").value("An unexpected error occurred"),
      content().string(not(containsString("secret internal detail"))));
  }

  /** The errors of Spring MVC itself get the same format from the base class. */
  @Test
  void answersUnsupportedMethodWith405() throws Exception {
    mockMvc.perform(post("/test/throw")).andExpectAll(
      status().isMethodNotAllowed(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.status").value(405));
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

    @PostMapping("/other")
    String other() {
      return "ok";
    }
  }
}
