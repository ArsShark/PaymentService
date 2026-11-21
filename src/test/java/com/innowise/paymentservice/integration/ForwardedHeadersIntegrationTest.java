package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentApiSupport.anna;
import static com.innowise.paymentservice.integration.PaymentApiSupport.idOf;
import static com.innowise.paymentservice.integration.PaymentApiSupport.pay;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Checks that the links of the service follow the {@code X-Forwarded-*} headers of the API
 * Gateway: a client that reaches the service through the Gateway must see the address of the
 * Gateway in the {@code Location} header of a created payment, not the internal host and port of
 * the service. The setting behind it is {@code server.forward-headers-strategy: framework}; the
 * Gateway sends the headers and drops the ones that a client sends itself, so this service may
 * trust them.
 */
class ForwardedHeadersIntegrationTest extends AbstractIntegrationTest {

  @BeforeEach
  void theRandomNumberServiceGivesAnEvenNumber() {
    RandomApiStub.answersWith(2);
  }

  @Test
  void theLocationOfACreatedPaymentUsesTheHostAndProtocolOfTheGateway() throws Exception {
    MvcResult result = created(pay(anna(), 706_001, "24.00")
      .header("X-Forwarded-Host", "shop.example.test")
      .header("X-Forwarded-Proto", "https"));

    assertEquals(String.format("https://shop.example.test/api/payments/%s", idOf(result)),
      locationOf(result));
  }

  @Test
  void thePortOfTheGatewayIsPartOfTheLocation() throws Exception {
    MvcResult result = created(pay(anna(), 706_002, "24.00")
      .header("X-Forwarded-Host", "shop.example.test")
      .header("X-Forwarded-Proto", "https")
      .header("X-Forwarded-Port", "8443"));

    assertEquals(String.format("https://shop.example.test:8443/api/payments/%s", idOf(result)),
      locationOf(result));
  }

  @Test
  void withoutForwardedHeadersTheLocationKeepsTheHostOfTheRequest() throws Exception {
    MvcResult result = created(pay(anna(), 706_003, "24.00"));

    assertEquals(String.format("http://localhost/api/payments/%s", idOf(result)),
      locationOf(result));
  }

  /** A failed payment is created too, so it gets its link in the same way. */
  @Test
  void aFailedPaymentHasTheSameKindOfLocation() throws Exception {
    RandomApiStub.answersWith(3);

    MvcResult result = created(pay(anna(), 706_004, "24.00")
      .header("X-Forwarded-Host", "shop.example.test")
      .header("X-Forwarded-Proto", "https"));

    assertEquals(String.format("https://shop.example.test/api/payments/%s", idOf(result)),
      locationOf(result));
  }

  private MvcResult created(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
  }

  private static String locationOf(MvcResult result) {
    return result.getResponse().getHeader(HttpHeaders.LOCATION);
  }
}
