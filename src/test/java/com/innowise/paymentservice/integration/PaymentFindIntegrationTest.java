package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.repository.PaymentFilter;
import com.innowise.paymentservice.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Tests the list of payments against a real MongoDB: every filter alone and combined, the
 * numbers of the page, the sort (also by the fields whose names differ from the properties), and
 * that pages of payments with equal sort values neither overlap nor skip.
 */
class PaymentFindIntegrationTest extends AbstractIntegrationTest {

  private static final Pageable FIRST_PAGE = PageRequest.of(0, 50);

  @Autowired
  private PaymentRepository repository;

  /**
   * Five payments of two users, saved in this order and returned in this order:
   * <pre>
   * p1 order 1 user 7 SUCCESS 2026-11-10  9.00
   * p2 order 2 user 7 FAILED  2026-11-11 10.00
   * p3 order 2 user 7 SUCCESS 2026-11-12 100.00
   * p4 order 3 user 8 SUCCESS 2026-11-12 24.00
   * p5 order 4 user 8 FAILED  2026-11-13  5.50
   * </pre>
   */
  private List<Payment> seed() {
    return List.of(
      repository.insert(payment(1L, 7L, PaymentStatus.SUCCESS, at("2026-11-10"), "9.00")),
      repository.insert(payment(2L, 7L, PaymentStatus.FAILED, at("2026-11-11"), "10.00")),
      repository.insert(payment(2L, 7L, PaymentStatus.SUCCESS, at("2026-11-12"), "100.00")),
      repository.insert(payment(3L, 8L, PaymentStatus.SUCCESS, at("2026-11-12"), "24.00")),
      repository.insert(payment(4L, 8L, PaymentStatus.FAILED, at("2026-11-13"), "5.50")));
  }

  private static Instant at(String day) {
    return Instant.parse(String.format("%sT10:00:00Z", day));
  }

  private static Set<String> idsOf(Page<Payment> page) {
    return page.getContent().stream().map(Payment::getId).collect(Collectors.toSet());
  }

  private static Set<String> ids(Payment... payments) {
    return Arrays.stream(payments).map(Payment::getId).collect(Collectors.toSet());
  }

  @Test
  void withoutAFilterEveryPaymentIsReturned() {
    seed();

    Page<Payment> page = repository.findAll(PaymentFilter.any(), FIRST_PAGE);

    assertAll(
      () -> assertEquals(5, page.getContent().size()),
      () -> assertEquals(5, page.getTotalElements()),
      () -> assertEquals(1, page.getTotalPages()));
  }

  @Test
  void aFilterByUserReturnsOnlyThePaymentsOfThatUser() {
    List<Payment> p = seed();

    assertAll(
      () -> assertEquals(ids(p.get(0), p.get(1), p.get(2)),
        idsOf(repository.findAll(new PaymentFilter(7L, null, null), FIRST_PAGE))),
      () -> assertEquals(ids(p.get(3), p.get(4)),
        idsOf(repository.findAll(new PaymentFilter(8L, null, null), FIRST_PAGE))),
      () -> assertEquals(0,
        repository.findAll(new PaymentFilter(9L, null, null), FIRST_PAGE).getTotalElements()));
  }

  @Test
  void aFilterByOrderReturnsEveryAttemptToPayThatOrder() {
    List<Payment> p = seed();

    assertEquals(ids(p.get(1), p.get(2)),
      idsOf(repository.findAll(new PaymentFilter(null, 2L, null), FIRST_PAGE)));
  }

  @Test
  void aFilterByStatusReturnsOnlyPaymentsWithThatStatus() {
    List<Payment> p = seed();

    assertAll(
      () -> assertEquals(ids(p.get(0), p.get(2), p.get(3)),
        idsOf(repository.findAll(new PaymentFilter(null, null, PaymentStatus.SUCCESS),
          FIRST_PAGE))),
      () -> assertEquals(ids(p.get(1), p.get(4)),
        idsOf(repository.findAll(new PaymentFilter(null, null, PaymentStatus.FAILED),
          FIRST_PAGE))));
  }

  @Test
  void severalFiltersAreCombinedWithAnd() {
    List<Payment> p = seed();

    assertAll(
      () -> assertEquals(ids(p.get(1)),
        idsOf(repository.findAll(new PaymentFilter(7L, null, PaymentStatus.FAILED),
          FIRST_PAGE))),
      () -> assertEquals(ids(p.get(1), p.get(2)),
        idsOf(repository.findAll(new PaymentFilter(7L, 2L, null), FIRST_PAGE))),
      () -> assertEquals(ids(p.get(2)),
        idsOf(repository.findAll(new PaymentFilter(7L, 2L, PaymentStatus.SUCCESS),
          FIRST_PAGE))),
      // The order exists, the user exists, but the order is not theirs.
      () -> assertEquals(0,
        repository.findAll(new PaymentFilter(8L, 2L, null), FIRST_PAGE).getTotalElements()));
  }

  @Test
  void aFilterThatMatchesNothingGivesAnEmptyPage() {
    seed();

    Page<Payment> page = repository.findAll(new PaymentFilter(7L, 99L, null), FIRST_PAGE);

    assertAll(
      () -> assertTrue(page.isEmpty()),
      () -> assertEquals(0, page.getTotalElements()),
      () -> assertEquals(0, page.getTotalPages()));
  }

  @Test
  void theNumbersOfThePageAreCountedOverAllMatchingPayments() {
    seed();

    Page<Payment> page = repository.findAll(PaymentFilter.any(), PageRequest.of(1, 2,
      Sort.by(Sort.Direction.ASC, "timestamp")));

    assertAll(
      () -> assertEquals(2, page.getContent().size()),
      () -> assertEquals(1, page.getNumber()),
      () -> assertEquals(5, page.getTotalElements()),
      () -> assertEquals(3, page.getTotalPages()),
      () -> assertTrue(page.hasNext()));
  }

  @Test
  void aPageBeyondTheEndIsEmptyButKnowsTheTotal() {
    seed();

    Page<Payment> page = repository.findAll(PaymentFilter.any(), PageRequest.of(5, 2));

    assertAll(
      () -> assertTrue(page.isEmpty()),
      () -> assertEquals(5, page.getTotalElements()));
  }

  @Test
  void paymentsWithTheSameSortValueAreSplitIntoPagesWithoutOverlapOrGaps() {
    List<Payment> saved = new ArrayList<>();
    // Five payments at the same moment: sorting by time alone cannot order them.
    for (long order = 1; order <= 5; order++) {
      saved.add(repository.insert(payment(order, 7L, PaymentStatus.FAILED)));
    }
    Sort byTime = Sort.by(Sort.Direction.DESC, "timestamp");

    List<String> seen = new ArrayList<>();
    for (int number = 0; number < 3; number++) {
      seen.addAll(repository.findAll(PaymentFilter.any(), PageRequest.of(number, 2, byTime))
        .getContent().stream().map(Payment::getId).toList());
    }

    // The id is the last sort key (ascending), so the order is exactly that of the ids.
    assertEquals(saved.stream().map(Payment::getId).sorted().toList(), seen);
  }

  @Test
  void aRequestWithoutASortIsStableToo() {
    List<Payment> saved = new ArrayList<>();
    for (long order = 1; order <= 5; order++) {
      saved.add(repository.insert(payment(order, 7L, PaymentStatus.FAILED)));
    }

    List<String> seen = new ArrayList<>();
    for (int number = 0; number < 3; number++) {
      seen.addAll(repository.findAll(PaymentFilter.any(), PageRequest.of(number, 2))
        .getContent().stream().map(Payment::getId).toList());
    }

    assertEquals(saved.stream().map(Payment::getId).sorted().toList(), seen);
  }

  @Test
  void theNewestPaymentsCanComeFirstAndTiesAreBrokenById() {
    List<Payment> p = seed();

    List<String> sorted = repository.findAll(PaymentFilter.any(),
        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "timestamp")))
      .getContent().stream().map(Payment::getId).toList();

    // p3 and p4 have the same time and come in the order of their ids.
    assertEquals(List.of(p.get(4), p.get(2), p.get(3), p.get(1), p.get(0)).stream()
      .map(Payment::getId).toList(), sorted);
  }

  @Test
  void theAmountIsSortedAsANumberNotAsText() {
    seed();

    List<BigDecimal> ascending = repository.findAll(PaymentFilter.any(),
        PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "paymentAmount")))
      .getContent().stream().map(Payment::getPaymentAmount).toList();
    List<BigDecimal> descending = repository.findAll(PaymentFilter.any(),
        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "paymentAmount")))
      .getContent().stream().map(Payment::getPaymentAmount).toList();

    assertAll(
      // As text "10.00" would come before "5.50" and "9.00".
      () -> assertEquals(List.of("5.50", "9.00", "10.00", "24.00", "100.00").stream()
        .map(BigDecimal::new).toList(), ascending),
      () -> assertEquals(List.of("100.00", "24.00", "10.00", "9.00", "5.50").stream()
        .map(BigDecimal::new).toList(), descending));
  }

  @Test
  void thePropertiesWithAnotherFieldNameCanBeSortedBy() {
    seed();

    List<Long> orders = repository.findAll(PaymentFilter.any(),
        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "orderId")))
      .getContent().stream().map(Payment::getOrderId).toList();
    List<Long> users = repository.findAll(PaymentFilter.any(),
        PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "userId")))
      .getContent().stream().map(Payment::getUserId).toList();
    List<PaymentStatus> statuses = repository.findAll(PaymentFilter.any(),
        PageRequest.of(0, 50, Sort.by(Sort.Direction.ASC, "status")))
      .getContent().stream().map(Payment::getStatus).toList();

    assertAll(
      () -> assertEquals(List.of(4L, 3L, 2L, 2L, 1L), orders),
      () -> assertEquals(List.of(7L, 7L, 7L, 8L, 8L), users),
      // The status is stored as its name, so FAILED comes before SUCCESS.
      () -> assertEquals(List.of(PaymentStatus.FAILED, PaymentStatus.FAILED,
        PaymentStatus.SUCCESS, PaymentStatus.SUCCESS, PaymentStatus.SUCCESS), statuses));
  }

  @Test
  void aRequestWithoutAPageIsRefused() {
    assertAll(
      () -> assertThrows(IllegalArgumentException.class,
        () -> repository.findAll(PaymentFilter.any(), Pageable.unpaged())),
      () -> assertThrows(NullPointerException.class,
        () -> repository.findAll(null, FIRST_PAGE)),
      () -> assertThrows(NullPointerException.class,
        () -> repository.findAll(PaymentFilter.any(), null)));
  }
}
