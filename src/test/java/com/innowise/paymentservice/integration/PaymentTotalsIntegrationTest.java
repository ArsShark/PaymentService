package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentRepository;
import com.innowise.paymentservice.repository.PaymentTotal;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tests the totals of the payments against a real MongoDB: which payments count, the edges of
 * the half-open period, the users, and that the decimals are added up exactly.
 */
class PaymentTotalsIntegrationTest extends AbstractIntegrationTest {

  private static final Instant FROM = Instant.parse("2026-11-10T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("2026-11-20T00:00:00Z");
  private static final Instant INSIDE = Instant.parse("2026-11-15T12:00:00Z");

  @Autowired
  private PaymentRepository repository;

  private void save(long orderId, long userId, PaymentStatus status, Instant time,
                    String amount) {
    repository.insert(payment(orderId, userId, status, time, amount));
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  @Test
  void onlySuccessfulPaymentsAreAddedUp() {
    save(1L, 7L, PaymentStatus.SUCCESS, INSIDE, "24.00");
    save(2L, 7L, PaymentStatus.FAILED, INSIDE, "10.50");
    save(3L, 7L, PaymentStatus.SUCCESS, INSIDE, "10.50");

    PaymentTotal total = repository.sumOfUser(7L, FROM, BEFORE);

    assertAll(
      () -> assertEquals(money("34.50"), total.total()),
      () -> assertEquals(2, total.count()));
  }

  @Test
  void aUserWithOnlyFailedPaymentsHasNothingToShow() {
    save(1L, 7L, PaymentStatus.FAILED, INSIDE, "24.00");

    assertEquals(new PaymentTotal(money("0.00"), 0), repository.sumOfUser(7L, FROM, BEFORE));
  }

  @Test
  void thePeriodStartsAtFromAndEndsBeforeBefore() {
    save(1L, 7L, PaymentStatus.SUCCESS, FROM.minusMillis(1), "8.00");
    save(2L, 7L, PaymentStatus.SUCCESS, FROM, "1.00");
    save(3L, 7L, PaymentStatus.SUCCESS, BEFORE.minusMillis(1), "2.00");
    save(4L, 7L, PaymentStatus.SUCCESS, BEFORE, "4.00");

    PaymentTotal total = repository.sumOfUser(7L, FROM, BEFORE);

    assertAll(
      // 1.00 (exactly at the start) and 2.00 (a millisecond before the end); 8.00 and 4.00 out.
      () -> assertEquals(money("3.00"), total.total()),
      () -> assertEquals(2, total.count()));
  }

  @Test
  void periodsPlacedOneAfterAnotherCountEveryPaymentExactlyOnce() {
    Instant middle = Instant.parse("2026-11-15T00:00:00Z");
    save(1L, 7L, PaymentStatus.SUCCESS, FROM, "1.00");
    save(2L, 7L, PaymentStatus.SUCCESS, middle, "2.00");
    save(3L, 7L, PaymentStatus.SUCCESS, BEFORE.minusMillis(1), "4.00");

    PaymentTotal first = repository.sumOfAll(FROM, middle);
    PaymentTotal second = repository.sumOfAll(middle, BEFORE);

    assertAll(
      () -> assertEquals(money("1.00"), first.total()),
      () -> assertEquals(money("6.00"), second.total()),
      () -> assertEquals(3, first.count() + second.count()));
  }

  @Test
  void theTotalOfAUserLeavesOutTheOtherUsers() {
    save(1L, 7L, PaymentStatus.SUCCESS, INSIDE, "24.00");
    save(2L, 8L, PaymentStatus.SUCCESS, INSIDE, "100.00");

    assertAll(
      () -> assertEquals(money("24.00"), repository.sumOfUser(7L, FROM, BEFORE).total()),
      () -> assertEquals(money("100.00"), repository.sumOfUser(8L, FROM, BEFORE).total()),
      () -> assertEquals(money("0.00"), repository.sumOfUser(9L, FROM, BEFORE).total()));
  }

  @Test
  void theTotalOfAllUsersAddsUpEveryone() {
    save(1L, 7L, PaymentStatus.SUCCESS, INSIDE, "24.00");
    save(2L, 8L, PaymentStatus.SUCCESS, INSIDE, "100.00");
    save(3L, 8L, PaymentStatus.FAILED, INSIDE, "5.00");

    PaymentTotal total = repository.sumOfAll(FROM, BEFORE);

    assertAll(
      () -> assertEquals(money("124.00"), total.total()),
      () -> assertEquals(2, total.count()));
  }

  @Test
  void aPeriodWithoutPaymentsIsZeroWithTwoDecimalPlaces() {
    save(1L, 7L, PaymentStatus.SUCCESS, BEFORE, "24.00");

    PaymentTotal empty = repository.sumOfAll(FROM, BEFORE);

    assertAll(
      () -> assertEquals(money("0.00"), empty.total()),
      () -> assertEquals(2, empty.total().scale()),
      () -> assertEquals(0, empty.count()));
  }

  @Test
  void anEmptyOrReversedPeriodIsZero() {
    save(1L, 7L, PaymentStatus.SUCCESS, INSIDE, "24.00");

    assertAll(
      () -> assertEquals(money("0.00"), repository.sumOfAll(INSIDE, INSIDE).total()),
      () -> assertEquals(money("0.00"), repository.sumOfAll(BEFORE, FROM).total()),
      () -> assertEquals(money("0.00"), repository.sumOfUser(7L, BEFORE, FROM).total()));
  }

  @Test
  void decimalsAreAddedUpExactly() {
    // With double, ten times 0.10 is 0.9999999999999999 and 0.10 + 0.20 is 0.30000000000000004.
    for (long order = 1; order <= 10; order++) {
      save(order, 7L, PaymentStatus.SUCCESS, INSIDE, "0.10");
    }
    save(11L, 8L, PaymentStatus.SUCCESS, INSIDE, "0.10");
    save(12L, 8L, PaymentStatus.SUCCESS, INSIDE, "0.20");

    assertAll(
      () -> assertEquals(money("1.00"), repository.sumOfUser(7L, FROM, BEFORE).total()),
      () -> assertEquals(money("0.30"), repository.sumOfUser(8L, FROM, BEFORE).total()),
      () -> assertEquals(money("1.30"), repository.sumOfAll(FROM, BEFORE).total()));
  }

  @Test
  void theLargestAmountsOfTheOrderServiceAreAddedUpWithoutLoss() {
    save(1L, 7L, PaymentStatus.SUCCESS, INSIDE, "9999999999.99");
    save(2L, 7L, PaymentStatus.SUCCESS, INSIDE, "9999999999.99");
    save(3L, 7L, PaymentStatus.SUCCESS, INSIDE, "9999999999.99");

    assertEquals(money("29999999999.97"), repository.sumOfUser(7L, FROM, BEFORE).total());
  }

  @Test
  void aMissingBoundIsRefused() {
    assertAll(
      () -> assertThrows(NullPointerException.class, () -> repository.sumOfAll(null, BEFORE)),
      () -> assertThrows(NullPointerException.class, () -> repository.sumOfAll(FROM, null)),
      () -> assertThrows(NullPointerException.class,
        () -> repository.sumOfUser(7L, null, BEFORE)),
      () -> assertThrows(NullPointerException.class,
        () -> repository.sumOfUser(7L, FROM, null)));
  }
}
