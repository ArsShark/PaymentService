package com.innowise.paymentservice.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Proves that the integration test infrastructure works: the application starts on a real
 * MongoDB, the changelog is applied, and the state is reset between tests, which every later
 * integration test relies on.
 */
class SmokeIntegrationTest extends AbstractIntegrationTest {

  @Test
  void resetStateEmptiesTheCollectionButKeepsTheSchema() {
    mongoTemplate.save(new Payment(
      1L, 7L, PaymentStatus.SUCCESS, Instant.parse("2026-11-13T10:00:00Z"),
      new BigDecimal("24.00")));
    assertEquals(1, mongoTemplate.getCollection(PAYMENTS).countDocuments());

    resetState();

    int indexes = mongoTemplate.getCollection(PAYMENTS).listIndexes()
      .into(new ArrayList<>()).size();
    assertAll(
      () -> assertEquals(0, mongoTemplate.getCollection(PAYMENTS).countDocuments()),
      // The index of _id and the four of the changelog.
      () -> assertEquals(5, indexes));
  }
}
