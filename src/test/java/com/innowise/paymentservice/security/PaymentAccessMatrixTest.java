package com.innowise.paymentservice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.innowise.paymentservice.config.ClockConfig;
import com.innowise.paymentservice.config.JwtConfig;
import com.innowise.paymentservice.config.SecurityConfig;
import com.innowise.paymentservice.controller.PaymentController;
import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.dto.response.PaymentTotalResponse;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentFilter;
import com.innowise.paymentservice.service.Caller;
import com.innowise.paymentservice.service.PaymentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Who may call what: every endpoint, and several that must not exist, against four kinds of
 * caller. The chain is the real one (URL rules and method security); the service is a mock that
 * answers every call, so the status of an allowed call is the success status and nothing but the
 * rules can produce a 401 or a 403.
 *
 * <p>The rows that matter most: {@code /total} is not the id of a payment (a user is refused),
 * an admin without a profile cannot pay or ask for own payments, a payment never changes (no
 * method that updates or deletes is open, even for an admin), and an unknown path is a 401 for an
 * anonymous caller and a 403 for an authenticated one, never a 404.
 */
@WebMvcTest(controllers = PaymentController.class,
  excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class})
class PaymentAccessMatrixTest {

  private static final String ID = "65f0c0ffee0123456789abcd";
  private static final String PERIOD_FROM = "2026-11-01T00:00:00Z";
  private static final String PERIOD_BEFORE = "2026-12-01T00:00:00Z";
  private static final String BODY = "{\"orderId\": 5, \"amount\": 24.00}";

  private static final int OK = 200;
  private static final int CREATED = 201;
  private static final int UNAUTHORIZED = 401;
  private static final int FORBIDDEN = 403;

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PaymentService paymentService;

  /**
   * One endpoint and what each kind of caller gets.
   *
   * @param name what the row is about
   * @param request builds a fresh request (a request builder collects post-processors)
   * @param anonymous the status without a token
   * @param user the status for a user with a profile
   * @param admin the status for an admin with a profile
   * @param adminWithoutProfile the status for an admin whose token has no {@code user_id}
   */
  record Row(String name, Supplier<MockHttpServletRequestBuilder> request, int anonymous,
    int user, int admin, int adminWithoutProfile) {

    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Row> rows() {
    return Stream.of(
      new Row("POST /api/payments", () -> post("/api/payments")
        .contentType(MediaType.APPLICATION_JSON).content(BODY),
        UNAUTHORIZED, CREATED, CREATED, FORBIDDEN),
      new Row("GET /api/payments", () -> get("/api/payments"),
        UNAUTHORIZED, FORBIDDEN, OK, OK),
      new Row("GET /api/payments/{id}", () -> get("/api/payments/{id}", ID),
        UNAUTHORIZED, OK, OK, OK),
      new Row("GET /api/payments/my", () -> get("/api/payments/my"),
        UNAUTHORIZED, OK, OK, FORBIDDEN),
      new Row("GET /api/payments/my/total", () -> get("/api/payments/my/total")
        .param("from", PERIOD_FROM).param("before", PERIOD_BEFORE),
        UNAUTHORIZED, OK, OK, FORBIDDEN),
      new Row("GET /api/payments/total", () -> get("/api/payments/total")
        .param("from", PERIOD_FROM).param("before", PERIOD_BEFORE),
        UNAUTHORIZED, FORBIDDEN, OK, OK),
      // A payment never changes: nothing that updates or deletes is open, not even to an admin.
      new Row("PUT /api/payments/{id}", () -> put("/api/payments/{id}", ID)
        .contentType(MediaType.APPLICATION_JSON).content(BODY),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      new Row("PATCH /api/payments/{id}", () -> patch("/api/payments/{id}", ID)
        .contentType(MediaType.APPLICATION_JSON).content(BODY),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      new Row("DELETE /api/payments/{id}", () -> delete("/api/payments/{id}", ID),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      new Row("DELETE /api/payments", () -> delete("/api/payments"),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      new Row("POST /api/payments/{id}", () -> post("/api/payments/{id}", ID)
        .contentType(MediaType.APPLICATION_JSON).content(BODY),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      new Row("POST /api/payments/total", () -> post("/api/payments/total")
        .contentType(MediaType.APPLICATION_JSON).content(BODY),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      // Paths that do not exist are closed by the chain before Spring MVC sees them.
      new Row("GET /api/payments/{id}/extra", () -> get("/api/payments/{id}/extra", ID),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      new Row("GET /api/orders", () -> get("/api/orders"),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN),
      new Row("GET /internal/payments", () -> get("/internal/payments"),
        UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN));
  }

  @BeforeEach
  void theServiceAnswersEverything() {
    Instant now = Instant.parse("2030-01-01T10:15:30Z");
    PaymentResponse payment = new PaymentResponse(ID, 5L, 7L, PaymentStatus.SUCCESS, now,
      new BigDecimal("24.00"));
    PaymentTotalResponse total = new PaymentTotalResponse(new BigDecimal("24.00"), 1, now, now);
    when(paymentService.create(any(Caller.class), any(PaymentRequest.class)))
      .thenReturn(payment);
    when(paymentService.getById(any(String.class), any(Caller.class))).thenReturn(payment);
    when(paymentService.findAll(any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(Page.empty());
    when(paymentService.findOwn(anyLong(), any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(Page.empty());
    when(paymentService.totalOfUser(anyLong(), any(Instant.class), any(Instant.class)))
      .thenReturn(total);
    when(paymentService.totalOfAll(any(Instant.class), any(Instant.class))).thenReturn(total);
  }

  @ParameterizedTest(name = "{0}: anonymous")
  @MethodSource("rows")
  void anonymousCaller(Row row) throws Exception {
    assertEquals(row.anonymous(), statusOf(row, null));
  }

  @ParameterizedTest(name = "{0}: user")
  @MethodSource("rows")
  void userWithAProfile(Row row) throws Exception {
    assertEquals(row.user(), statusOf(row, token("ROLE_USER", 7L)));
  }

  @ParameterizedTest(name = "{0}: admin")
  @MethodSource("rows")
  void adminWithAProfile(Row row) throws Exception {
    assertEquals(row.admin(), statusOf(row, token("ROLE_ADMIN", 1L)));
  }

  @ParameterizedTest(name = "{0}: admin without a profile")
  @MethodSource("rows")
  void adminWithoutAProfile(Row row) throws Exception {
    assertEquals(row.adminWithoutProfile(), statusOf(row, token("ROLE_ADMIN", null)));
  }

  /** A token whose role is neither ADMIN nor USER opens nothing. */
  @ParameterizedTest(name = "{0}: unknown role")
  @MethodSource("rows")
  void aRoleThatIsNotKnownIsRefusedEverywhere(Row row) throws Exception {
    int status = statusOf(row, token("ROLE_SUPPORT", 7L));

    assertEquals(FORBIDDEN, status);
  }

  private int statusOf(Row row, RequestPostProcessor token) throws Exception {
    MockHttpServletRequestBuilder request = row.request().get();
    if (token != null) {
      request = request.with(token);
    }
    return mockMvc.perform(request).andReturn().getResponse().getStatus();
  }

  private static RequestPostProcessor token(String authority, Long userId) {
    return jwt().jwt(token -> {
      if (userId != null) {
        token.claim("user_id", userId);
      }
      token.claim("email", "caller@example.com");
    }).authorities(List.of(new SimpleGrantedAuthority(authority)));
  }
}
