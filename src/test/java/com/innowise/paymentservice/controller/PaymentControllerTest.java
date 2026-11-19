package com.innowise.paymentservice.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.paymentservice.config.ClockConfig;
import com.innowise.paymentservice.config.JwtConfig;
import com.innowise.paymentservice.config.SecurityConfig;
import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.dto.response.PaymentTotalResponse;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.exception.InvalidPeriodException;
import com.innowise.paymentservice.exception.OrderAlreadyPaidException;
import com.innowise.paymentservice.exception.PaymentNotFoundException;
import com.innowise.paymentservice.exception.RandomNumberTimeoutException;
import com.innowise.paymentservice.exception.RandomNumberUnavailableException;
import com.innowise.paymentservice.repository.PaymentFilter;
import com.innowise.paymentservice.service.Caller;
import com.innowise.paymentservice.service.PaymentService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP contract tests of {@link PaymentController}: paths, status codes, headers, JSON shape, and
 * what the controller makes of the token. The service is a mock, so these tests do not touch a
 * database or the random number service; the full flow is covered by the integration tests.
 *
 * <p>The security chain is real. The token is placed in the security context by the
 * {@code jwt()} post-processor, so the decoder is not used, but the controller sees exactly what
 * it sees in production: a {@code Jwt} principal with its claims and authorities.
 */
@WebMvcTest(controllers = PaymentController.class,
  excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtConfig.class, ClockConfig.class})
class PaymentControllerTest {

  private static final Instant NOW = Instant.parse("2030-01-01T10:15:30Z");

  private static final String PAYMENT_ID = "65f0c0ffee0123456789abcd";

  private static final String VALID_PAYMENT_JSON = """
    {"orderId": 5, "amount": 24.00}
    """;

  private static final Caller BUYER = new Caller(7L, false);

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PaymentService paymentService;

  private static RequestPostProcessor user() {
    return jwt().jwt(token -> token.claim("user_id", 7L))
      .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  private static RequestPostProcessor admin() {
    return jwt().jwt(token -> token.claim("user_id", 1L))
      .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static RequestPostProcessor adminWithoutProfile() {
    return jwt().jwt(token -> token.claim("email", "admin@example.com"))
      .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private static PaymentResponse payment(PaymentStatus status) {
    return new PaymentResponse(PAYMENT_ID, 5L, 7L, status, NOW, new BigDecimal("24.00"));
  }

  private static PageImpl<PaymentResponse> pageOf(PaymentResponse... payments) {
    return new PageImpl<>(List.of(payments), PageRequest.of(0, 20), payments.length);
  }

  private Pageable pageableSentToFindAll() {
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(paymentService).findAll(any(PaymentFilter.class), pageable.capture());
    return pageable.getValue();
  }

  // ---- create

  @Test
  void createReturns201WithLocationBodyAndThePayerFromTheToken() throws Exception {
    when(paymentService.create(eq(BUYER), any(PaymentRequest.class)))
      .thenReturn(payment(PaymentStatus.SUCCESS));

    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_PAYMENT_JSON)).andExpectAll(
      status().isCreated(),
      header().string(HttpHeaders.LOCATION,
        String.format("http://localhost/api/payments/%s", PAYMENT_ID)),
      jsonPath("$.id").value(PAYMENT_ID),
      jsonPath("$.orderId").value(5),
      jsonPath("$.userId").value(7),
      jsonPath("$.status").value("SUCCESS"),
      jsonPath("$.timestamp").value(NOW.toString()),
      jsonPath("$.amount").value(24.00));

    ArgumentCaptor<PaymentRequest> request = ArgumentCaptor.forClass(PaymentRequest.class);
    verify(paymentService).create(eq(BUYER), request.capture());
    assertEquals(new PaymentRequest(5L, new BigDecimal("24.00")), request.getValue());
  }

  /** A declined payment is still a created record: the outcome is data, not an error. */
  @Test
  void createReturns201AlsoForAFailedPayment() throws Exception {
    when(paymentService.create(eq(BUYER), any(PaymentRequest.class)))
      .thenReturn(payment(PaymentStatus.FAILED));

    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_PAYMENT_JSON)).andExpectAll(
      status().isCreated(),
      jsonPath("$.status").value("FAILED"));
  }

  /** The body has no payer or outcome: fields that a client adds are not used. */
  @Test
  void createTakesThePayerFromTheTokenWhateverTheBodySays() throws Exception {
    when(paymentService.create(any(Caller.class), any(PaymentRequest.class)))
      .thenReturn(payment(PaymentStatus.SUCCESS));
    String body = """
      {"userId": 99, "status": "SUCCESS", "timestamp": "2001-01-01T00:00:00Z",
       "orderId": 5, "amount": 24.00}
      """;

    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());

    verify(paymentService).create(eq(BUYER), eq(new PaymentRequest(5L, new BigDecimal("24.00"))));
  }

  @Test
  void anAdminWithAProfileMayPayForHimself() throws Exception {
    when(paymentService.create(any(Caller.class), any(PaymentRequest.class)))
      .thenReturn(payment(PaymentStatus.SUCCESS));

    mockMvc.perform(post("/api/payments").with(admin())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_PAYMENT_JSON)).andExpect(status().isCreated());

    verify(paymentService).create(eq(new Caller(1L, true)), any(PaymentRequest.class));
  }

  @Test
  void createIsRefusedWith403ForATokenWithoutAProfileAndTheServiceIsNeverCalled()
    throws Exception {
    mockMvc.perform(post("/api/payments").with(adminWithoutProfile())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_PAYMENT_JSON)).andExpectAll(
      status().isForbidden(),
      jsonPath("$.detail").value("Access is denied"));

    verifyNoInteractions(paymentService);
  }

  @Test
  void createRejectsAnInvalidBodyWithTheListOfFieldsAndWithoutTheValues() throws Exception {
    String body = """
      {"orderId": -987654, "amount": 0}
      """;

    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON).content(body)).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
      jsonPath("$.errors.length()").value(2),
      jsonPath("$.errors[0].field").value("amount"),
      jsonPath("$.errors[1].field").value("orderId"),
      content().string(not(containsString("987654"))));

    verifyNoInteractions(paymentService);
  }

  @Test
  void createRejectsAnAmountWithMoreThanTwoDecimalPlaces() throws Exception {
    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"orderId\": 5, \"amount\": 24.001}")).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.errors[0].field").value("amount"));

    verifyNoInteractions(paymentService);
  }

  @Test
  void createRejectsAMissingAmountAndAMissingOrder() throws Exception {
    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpectAll(
      status().isBadRequest(),
      jsonPath("$.errors.length()").value(2));

    verifyNoInteractions(paymentService);
  }

  @Test
  void createRejectsMalformedJsonWith400() throws Exception {
    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\": 5, \"amount\": "))
      .andExpect(status().isBadRequest());

    verifyNoInteractions(paymentService);
  }

  @Test
  void createRejectsAnotherContentTypeWith415() throws Exception {
    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.TEXT_PLAIN).content(VALID_PAYMENT_JSON))
      .andExpect(status().isUnsupportedMediaType());

    verifyNoInteractions(paymentService);
  }

  @Test
  void createAnswers409WhenTheOrderIsAlreadyPaid() throws Exception {
    when(paymentService.create(any(Caller.class), any(PaymentRequest.class)))
      .thenThrow(new OrderAlreadyPaidException(5L));

    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_PAYMENT_JSON)).andExpectAll(
      status().isConflict(),
      jsonPath("$.detail").value("Order 5 is already paid"));
  }

  @Test
  void createAnswers502WhenTheRandomNumberServiceIsNotAvailable() throws Exception {
    when(paymentService.create(any(Caller.class), any(PaymentRequest.class)))
      .thenThrow(new RandomNumberUnavailableException());

    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_PAYMENT_JSON)).andExpectAll(
      status().isBadGateway(),
      jsonPath("$.detail").value("The random number service is not available"));
  }

  @Test
  void createAnswers504WhenTheRandomNumberServiceIsTooSlow() throws Exception {
    when(paymentService.create(any(Caller.class), any(PaymentRequest.class)))
      .thenThrow(new RandomNumberTimeoutException());

    mockMvc.perform(post("/api/payments").with(user())
      .contentType(MediaType.APPLICATION_JSON)
      .content(VALID_PAYMENT_JSON)).andExpectAll(
      status().isGatewayTimeout(),
      jsonPath("$.detail").value("The random number service did not answer in time"));
  }

  // ---- getById

  @Test
  void getByIdReturnsThePaymentForItsPayer() throws Exception {
    when(paymentService.getById(eq(PAYMENT_ID), eq(BUYER)))
      .thenReturn(payment(PaymentStatus.SUCCESS));

    mockMvc.perform(get("/api/payments/{id}", PAYMENT_ID).with(user())).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(PAYMENT_ID),
      jsonPath("$.userId").value(7),
      jsonPath("$.amount").value(24.00));
  }

  @Test
  void getByIdPassesAnAdminToTheServiceWithOrWithoutAProfile() throws Exception {
    when(paymentService.getById(eq(PAYMENT_ID), any(Caller.class)))
      .thenReturn(payment(PaymentStatus.FAILED));

    mockMvc.perform(get("/api/payments/{id}", PAYMENT_ID).with(admin()))
      .andExpect(status().isOk());
    mockMvc.perform(get("/api/payments/{id}", PAYMENT_ID).with(adminWithoutProfile()))
      .andExpect(status().isOk());

    verify(paymentService).getById(PAYMENT_ID, new Caller(1L, true));
    verify(paymentService).getById(PAYMENT_ID, new Caller(null, true));
  }

  /** The service decides; a missing payment and the payment of somebody else look the same. */
  @Test
  void getByIdAnswers404WithAConstantDetail() throws Exception {
    when(paymentService.getById(eq(PAYMENT_ID), any(Caller.class)))
      .thenThrow(new PaymentNotFoundException());

    mockMvc.perform(get("/api/payments/{id}", PAYMENT_ID).with(user())).andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("Payment not found"),
      content().string(not(containsString(PAYMENT_ID))));
  }

  // ---- getAll (admin)

  @Test
  void getAllReturnsAPageShapedForTheClient() throws Exception {
    when(paymentService.findAll(any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf(payment(PaymentStatus.SUCCESS)));

    mockMvc.perform(get("/api/payments").with(admin())).andExpectAll(
      status().isOk(),
      jsonPath("$.content.length()").value(1),
      jsonPath("$.content[0].id").value(PAYMENT_ID),
      jsonPath("$.page").value(0),
      jsonPath("$.size").value(20),
      jsonPath("$.totalElements").value(1),
      jsonPath("$.totalPages").value(1));
  }

  /** Newest first, 20 per page, and id as the last key so pages never overlap. */
  @Test
  void getAllUsesTheDefaultPageAndSort() throws Exception {
    when(paymentService.findAll(any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/payments").with(admin())).andExpect(status().isOk());

    Pageable pageable = pageableSentToFindAll();
    assertAll(
      () -> assertEquals(0, pageable.getPageNumber()),
      () -> assertEquals(20, pageable.getPageSize()),
      () -> assertEquals(Sort.by(Sort.Order.desc("timestamp"), Sort.Order.asc("id")),
        pageable.getSort()));
  }

  @Test
  void getAllPassesTheFilterToTheService() throws Exception {
    when(paymentService.findAll(any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/payments").with(admin())
      .param("userId", "7").param("orderId", "5").param("status", "FAILED"))
      .andExpect(status().isOk());

    verify(paymentService).findAll(eq(new PaymentFilter(7L, 5L, PaymentStatus.FAILED)),
      any(Pageable.class));
  }

  @Test
  void getAllWithNoParametersSetsNoCondition() throws Exception {
    when(paymentService.findAll(any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/payments").with(admin())).andExpect(status().isOk());

    verify(paymentService).findAll(eq(PaymentFilter.any()), any(Pageable.class));
  }

  /** The client says "amount"; the query gets the property of the entity. */
  @Test
  void getAllTranslatesTheSortByAmount() throws Exception {
    when(paymentService.findAll(any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/payments").with(admin()).param("sort", "amount,desc"))
      .andExpect(status().isOk());

    assertEquals(Sort.by(Sort.Order.desc("paymentAmount"), Sort.Order.asc("id")),
      pageableSentToFindAll().getSort());
  }

  @Test
  void getAllRefusesTheNameOfTheEntityAndDoesNotEchoIt() throws Exception {
    mockMvc.perform(get("/api/payments").with(admin()).param("sort", "paymentAmount"))
      .andExpectAll(
        status().isBadRequest(),
        jsonPath("$.detail").value(String.format(
          "Unsupported sort property. Allowed properties: %s",
          "[amount, id, orderId, status, timestamp, userId]")),
        content().string(not(containsString("paymentAmount"))));

    verifyNoInteractions(paymentService);
  }

  /** The default of Spring Data is 2000: the limit of the project is set in application.yml. */
  @Test
  void getAllCapsThePageSizeAt100() throws Exception {
    when(paymentService.findAll(any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/payments").with(admin()).param("size", "1000"))
      .andExpect(status().isOk());

    assertEquals(100, pageableSentToFindAll().getPageSize());
  }

  @Test
  void getAllDoesNotEchoAStatusThatCannotBeRead() throws Exception {
    mockMvc.perform(get("/api/payments").with(admin()).param("status", "BOGUS-VALUE"))
      .andExpectAll(
        status().isBadRequest(),
        jsonPath("$.detail").value("Invalid value of the parameter 'status'"),
        content().string(not(containsString("BOGUS-VALUE"))));

    verifyNoInteractions(paymentService);
  }

  @Test
  void getAllRejectsAnOrderIdThatIsNotANumber() throws Exception {
    mockMvc.perform(get("/api/payments").with(admin()).param("orderId", "abc"))
      .andExpectAll(
        status().isBadRequest(),
        jsonPath("$.detail").value("Invalid value of the parameter 'orderId'"));
  }

  @Test
  void getAllIsForbiddenForAUserAndTheServiceIsNeverCalled() throws Exception {
    mockMvc.perform(get("/api/payments").with(user())).andExpectAll(
      status().isForbidden(),
      jsonPath("$.detail").value("Access is denied"));

    verifyNoInteractions(paymentService);
  }

  // ---- getMy

  @Test
  void getMyAsksForThePaymentsOfTheCallerOnly() throws Exception {
    when(paymentService.findOwn(eq(7L), any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf(payment(PaymentStatus.SUCCESS)));

    mockMvc.perform(get("/api/payments/my").with(user())
      .param("orderId", "5").param("status", "SUCCESS")).andExpectAll(
      status().isOk(),
      jsonPath("$.content[0].userId").value(7),
      jsonPath("$.totalElements").value(1));

    verify(paymentService).findOwn(eq(7L),
      eq(new PaymentFilter(null, 5L, PaymentStatus.SUCCESS)), any(Pageable.class));
  }

  /** There is no payer parameter: a {@code userId} that a client adds is never used. */
  @Test
  void getMyIgnoresAUserIdParameter() throws Exception {
    when(paymentService.findOwn(eq(7L), any(PaymentFilter.class), any(Pageable.class)))
      .thenReturn(pageOf());

    mockMvc.perform(get("/api/payments/my").with(user()).param("userId", "99"))
      .andExpect(status().isOk());

    ArgumentCaptor<PaymentFilter> filter = ArgumentCaptor.forClass(PaymentFilter.class);
    verify(paymentService).findOwn(eq(7L), filter.capture(), any(Pageable.class));
    assertNull(filter.getValue().userId());
  }

  @Test
  void getMyIsRefusedWith403ForATokenWithoutAProfile() throws Exception {
    mockMvc.perform(get("/api/payments/my").with(adminWithoutProfile()))
      .andExpect(status().isForbidden());

    verifyNoInteractions(paymentService);
  }

  // ---- totals

  @Test
  void getMyTotalSumsThePeriodOfTheCaller() throws Exception {
    Instant from = Instant.parse("2026-11-01T00:00:00Z");
    Instant before = Instant.parse("2026-12-01T00:00:00Z");
    when(paymentService.totalOfUser(7L, from, before))
      .thenReturn(new PaymentTotalResponse(new BigDecimal("48.00"), 2, from, before));

    mockMvc.perform(get("/api/payments/my/total").with(user())
      .param("from", "2026-11-01T00:00:00Z").param("before", "2026-12-01T00:00:00Z"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.total").value(48.00),
        jsonPath("$.count").value(2),
        jsonPath("$.from").value("2026-11-01T00:00:00Z"),
        jsonPath("$.before").value("2026-12-01T00:00:00Z"));
  }

  @Test
  void getMyTotalIsRefusedWith403ForATokenWithoutAProfile() throws Exception {
    mockMvc.perform(get("/api/payments/my/total").with(adminWithoutProfile())
      .param("from", "2026-11-01T00:00:00Z").param("before", "2026-12-01T00:00:00Z"))
      .andExpect(status().isForbidden());

    verifyNoInteractions(paymentService);
  }

  @Test
  void getTotalSumsThePeriodOfEverybodyForAnAdmin() throws Exception {
    Instant from = Instant.parse("2026-11-01T00:00:00Z");
    Instant before = Instant.parse("2026-12-01T00:00:00Z");
    when(paymentService.totalOfAll(from, before))
      .thenReturn(new PaymentTotalResponse(new BigDecimal("1234.50"), 9, from, before));

    mockMvc.perform(get("/api/payments/total").with(admin())
      .param("from", "2026-11-01T00:00:00Z").param("before", "2026-12-01T00:00:00Z"))
      .andExpectAll(
        status().isOk(),
        jsonPath("$.total").value(1234.50),
        jsonPath("$.count").value(9));
  }

  /** "/total" must not be taken for the id of a payment, which a user may read. */
  @Test
  void getTotalIsForbiddenForAUserAndTheServiceIsNeverCalled() throws Exception {
    mockMvc.perform(get("/api/payments/total").with(user())
      .param("from", "2026-11-01T00:00:00Z").param("before", "2026-12-01T00:00:00Z"))
      .andExpectAll(
        status().isForbidden(),
        jsonPath("$.detail").value("Access is denied"));

    verify(paymentService, never()).totalOfAll(any(Instant.class), any(Instant.class));
    verify(paymentService, never()).getById(any(String.class), any(Caller.class));
  }

  @Test
  void aTotalNeedsBothBounds() throws Exception {
    mockMvc.perform(get("/api/payments/total").with(admin())
      .param("from", "2026-11-01T00:00:00Z")).andExpectAll(
      status().isBadRequest(),
      content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mockMvc.perform(get("/api/payments/my/total").with(user())
      .param("before", "2026-11-01T00:00:00Z")).andExpect(status().isBadRequest());

    verifyNoInteractions(paymentService);
  }

  @Test
  void aBoundThatIsNotAnInstantIsRefusedWithoutEchoingIt() throws Exception {
    mockMvc.perform(get("/api/payments/total").with(admin())
      .param("from", "yesterday-secret").param("before", "2026-11-01T00:00:00Z"))
      .andExpectAll(
        status().isBadRequest(),
        jsonPath("$.detail").value("Invalid value of the parameter 'from'"),
        content().string(not(containsString("yesterday-secret"))));
  }

  @Test
  void anEmptyPeriodIsA400FromTheService() throws Exception {
    when(paymentService.totalOfAll(any(Instant.class), any(Instant.class)))
      .thenThrow(new InvalidPeriodException());

    mockMvc.perform(get("/api/payments/total").with(admin())
      .param("from", "2026-11-01T00:00:00Z").param("before", "2026-11-01T00:00:00Z"))
      .andExpectAll(
        status().isBadRequest(),
        jsonPath("$.detail").value(new InvalidPeriodException().getMessage()));
  }

  // ---- no token

  @Test
  void everyEndpointNeedsAToken() throws Exception {
    mockMvc.perform(get("/api/payments/{id}", PAYMENT_ID)).andExpectAll(
      status().isUnauthorized(),
      jsonPath("$.detail").value("Authentication is required"));

    verifyNoInteractions(paymentService);
  }
}
