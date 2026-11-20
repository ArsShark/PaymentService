package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.innowise.paymentservice.TestTokens;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The endpoints with real tokens (verified with the key set of a small HTTP server), the real
 * chain and a real MongoDB: who sees which payments, and what a refused request leaves behind.
 *
 * <p>No test here makes a payment that reaches the random number service: a payment that is
 * accepted needs that service, and the tests of that flow play it with WireMock. A request that
 * must be refused is refused before the service is called, and a test proves that nothing was
 * stored.
 */
class ApiSecurityIntegrationTest extends AbstractIntegrationTest {

  private static final String PERIOD_FROM = "2026-11-01T00:00:00Z";
  private static final String PERIOD_BEFORE = "2026-12-01T00:00:00Z";
  private static final Instant IN_PERIOD = Instant.parse("2026-11-14T12:00:00Z");

  @Autowired
  private PaymentRepository paymentRepository;

  private static String user(long userId) {
    return TestTokens.access().userId(userId).build();
  }

  private static String adminWithProfile() {
    return TestTokens.admin().userId(1L).build();
  }

  private ResultActions getAs(String token, String path) throws Exception {
    return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(token)));
  }

  @Test
  void aUserListsOnlyHisOwnPayments() throws Exception {
    paymentRepository.insert(payment(1, 7, PaymentStatus.SUCCESS));
    paymentRepository.insert(payment(2, 7, PaymentStatus.FAILED));
    paymentRepository.insert(payment(3, 8, PaymentStatus.SUCCESS));

    getAs(user(7), "/api/payments/my").andExpectAll(
      status().isOk(),
      jsonPath("$.totalElements").value(2),
      jsonPath("$.content.length()").value(2),
      jsonPath("$.content[?(@.userId != 7)]").isEmpty());
  }

  /** The payer is the token: a {@code userId} in the query changes nothing. */
  @Test
  void aUserIdInTheQueryOfTheOwnListChangesNothing() throws Exception {
    paymentRepository.insert(payment(1, 7, PaymentStatus.SUCCESS));
    paymentRepository.insert(payment(3, 8, PaymentStatus.SUCCESS));

    getAs(user(7), "/api/payments/my?userId=8").andExpectAll(
      status().isOk(),
      jsonPath("$.totalElements").value(1),
      jsonPath("$.content[0].userId").value(7));
  }

  /** A payment of somebody else is answered exactly like one that does not exist. */
  @Test
  void aPaymentOfSomebodyElseLooksLikeAMissingOne() throws Exception {
    Payment foreign = paymentRepository.insert(payment(3, 8, PaymentStatus.SUCCESS));
    String missingId = "65f0c0ffee0123456789abcd";

    String foreignDetail = detailOf(getAs(user(7), String.format("/api/payments/%s",
      foreign.getId())).andExpect(status().isNotFound()));
    String missingDetail = detailOf(getAs(user(7), String.format("/api/payments/%s", missingId))
      .andExpect(status().isNotFound()));

    assertEquals(missingDetail, foreignDetail);
  }

  @Test
  void anIdThatIsNotAnIdAtAllIsA404TooAndNotAnError() throws Exception {
    getAs(user(7), "/api/payments/not-an-id").andExpectAll(
      status().isNotFound(),
      jsonPath("$.detail").value("Payment not found"));
  }

  @Test
  void aUserReadsHisOwnPaymentById() throws Exception {
    Payment own = paymentRepository.insert(payment(1, 7, PaymentStatus.SUCCESS));

    getAs(user(7), String.format("/api/payments/%s", own.getId())).andExpectAll(
      status().isOk(),
      jsonPath("$.id").value(own.getId()),
      jsonPath("$.orderId").value(1),
      jsonPath("$.userId").value(7),
      jsonPath("$.status").value("SUCCESS"),
      jsonPath("$.amount").value(24.00));
  }

  @Test
  void anAdminListsEverybodysPaymentsAndReadsAnyOfThem() throws Exception {
    Payment foreign = paymentRepository.insert(payment(3, 8, PaymentStatus.SUCCESS));
    paymentRepository.insert(payment(1, 7, PaymentStatus.FAILED));

    assertAll(
      () -> mockMvc.perform(get("/api/payments")).andExpectAll(
        status().isOk(),
        jsonPath("$.totalElements").value(2)),
      () -> mockMvc.perform(get(String.format("/api/payments/%s", foreign.getId())))
        .andExpectAll(
          status().isOk(),
          jsonPath("$.userId").value(8)));
  }

  @Test
  void anAdminFiltersTheListByPayerOrderAndStatus() throws Exception {
    paymentRepository.insert(payment(1, 7, PaymentStatus.FAILED));
    paymentRepository.insert(payment(1, 7, PaymentStatus.SUCCESS));
    paymentRepository.insert(payment(2, 7, PaymentStatus.SUCCESS));
    paymentRepository.insert(payment(3, 8, PaymentStatus.SUCCESS));

    mockMvc.perform(get("/api/payments?userId=7&orderId=1&status=SUCCESS")).andExpectAll(
      status().isOk(),
      jsonPath("$.totalElements").value(1),
      jsonPath("$.content[0].orderId").value(1),
      jsonPath("$.content[0].status").value("SUCCESS"));
  }

  @Test
  void aUserIsRefusedTheListAndTheTotalOfEverybody() throws Exception {
    paymentRepository.insert(payment(3, 8, PaymentStatus.SUCCESS));

    assertAll(
      () -> getAs(user(7), "/api/payments").andExpectAll(
        status().isForbidden(), problemJson(),
        jsonPath("$.detail").value("Access is denied")),
      () -> getAs(user(7), String.format("/api/payments/total?from=%s&before=%s",
        PERIOD_FROM, PERIOD_BEFORE)).andExpectAll(
        status().isForbidden(), problemJson(),
        jsonPath("$.detail").value("Access is denied")));
  }

  /** The total counts the successful payments of the caller, inside the half-open period. */
  @Test
  void theOwnTotalCountsOnlyTheSuccessfulPaymentsOfTheCallerInThePeriod() throws Exception {
    paymentRepository.insert(payment(1, 7, PaymentStatus.SUCCESS, IN_PERIOD, "24.00"));
    paymentRepository.insert(payment(2, 7, PaymentStatus.SUCCESS, IN_PERIOD, "10.50"));
    paymentRepository.insert(payment(3, 7, PaymentStatus.FAILED, IN_PERIOD, "99.00"));
    paymentRepository.insert(payment(4, 8, PaymentStatus.SUCCESS, IN_PERIOD, "77.00"));
    paymentRepository.insert(payment(5, 7, PaymentStatus.SUCCESS,
      Instant.parse(PERIOD_BEFORE), "5.00"));

    getAs(user(7), String.format("/api/payments/my/total?from=%s&before=%s",
      PERIOD_FROM, PERIOD_BEFORE)).andExpectAll(
      status().isOk(),
      jsonPath("$.total").value(34.50),
      jsonPath("$.count").value(2),
      jsonPath("$.from").value(PERIOD_FROM),
      jsonPath("$.before").value(PERIOD_BEFORE));
  }

  @Test
  void theTotalOfEverybodyCountsEveryPayerForAnAdmin() throws Exception {
    paymentRepository.insert(payment(1, 7, PaymentStatus.SUCCESS, IN_PERIOD, "24.00"));
    paymentRepository.insert(payment(4, 8, PaymentStatus.SUCCESS, IN_PERIOD, "77.00"));
    paymentRepository.insert(payment(3, 7, PaymentStatus.FAILED, IN_PERIOD, "99.00"));

    mockMvc.perform(get(String.format("/api/payments/total?from=%s&before=%s",
      PERIOD_FROM, PERIOD_BEFORE))).andExpectAll(
      status().isOk(),
      jsonPath("$.total").value(101.00),
      jsonPath("$.count").value(2));
  }

  @Test
  void aTotalOfAPeriodWithoutPaymentsIsZero() throws Exception {
    getAs(user(7), String.format("/api/payments/my/total?from=%s&before=%s",
      PERIOD_FROM, PERIOD_BEFORE)).andExpectAll(
      status().isOk(),
      jsonPath("$.total").value(0.00),
      jsonPath("$.count").value(0));
  }

  @Test
  void anEmptyPeriodIsA400() throws Exception {
    getAs(adminWithProfile(), String.format("/api/payments/my/total?from=%s&before=%s",
      PERIOD_FROM, PERIOD_FROM)).andExpectAll(
      status().isBadRequest(), problemJson());
  }

  @Test
  void anInvalidPaymentIsRefusedAndNothingIsStored() throws Exception {
    mockMvc.perform(post("/api/payments")
      .header(HttpHeaders.AUTHORIZATION, bearer(user(7)))
      .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpectAll(
      status().isBadRequest(), problemJson(),
      jsonPath("$.errors.length()").value(2));

    assertEquals(0, mongoTemplate.getCollection(PAYMENTS).countDocuments());
  }

  /** The default token of MockMvc is an admin without a profile: he has no payer to pay as. */
  @Test
  void aTokenWithoutAProfileCannotPayAndNothingIsStored() throws Exception {
    mockMvc.perform(post("/api/payments")
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"orderId\": 5, \"amount\": 24.00}")).andExpectAll(
      status().isForbidden(), problemJson(),
      jsonPath("$.detail").value("Access is denied"));

    assertEquals(0, mongoTemplate.getCollection(PAYMENTS).countDocuments());
  }

  @Test
  void aPaymentCannotBeChangedOrDeletedByAnyone() throws Exception {
    Payment own = paymentRepository.insert(payment(1, 7, PaymentStatus.SUCCESS));
    String path = String.format("/api/payments/%s", own.getId());

    assertAll(
      () -> mockMvc.perform(delete(path)).andExpect(status().isForbidden()),
      () -> mockMvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
        .content("{\"orderId\": 1, \"amount\": 1.00}")).andExpect(status().isForbidden()),
      () -> assertEquals(1, mongoTemplate.getCollection(PAYMENTS).countDocuments()));
  }

  private static String detailOf(ResultActions actions) throws Exception {
    String body = actions.andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.detail");
  }
}
