package com.innowise.paymentservice.integration;

import static com.innowise.paymentservice.integration.PaymentTestData.payment;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.exception.ViolatedIndex;
import com.innowise.paymentservice.repository.PaymentRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

/**
 * Tests creating a payment and reading it by id against a real MongoDB: what is stored, the
 * rule "one successful payment per order" (also under parallel requests), and that the
 * repository cannot change or delete a payment.
 */
class PaymentInsertIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private PaymentRepository repository;

  private long countPayments() {
    return mongoTemplate.getCollection(PAYMENTS).countDocuments();
  }

  @Test
  void aSavedPaymentGetsAnIdAndIsReadBackUnchanged() {
    Instant timestamp = Instant.parse("2026-11-13T10:15:30.123456Z");

    Payment saved = repository.insert(
      payment(11L, 7L, PaymentStatus.FAILED, timestamp, "24.5"));
    Payment loaded = repository.findById(saved.getId()).orElseThrow();

    assertAll(
      () -> assertNotNull(saved.getId()),
      () -> assertTrue(ObjectId.isValid(saved.getId())),
      () -> assertEquals(saved.getId(), loaded.getId()),
      () -> assertEquals(11L, loaded.getOrderId()),
      () -> assertEquals(7L, loaded.getUserId()),
      () -> assertEquals(PaymentStatus.FAILED, loaded.getStatus()),
      () -> assertEquals(Instant.parse("2026-11-13T10:15:30.123Z"), loaded.getTimestamp()),
      () -> assertEquals(new BigDecimal("24.50"), loaded.getPaymentAmount()),
      () -> assertEquals(1, countPayments()));
  }

  @Test
  void anUnknownIdGivesAnEmptyResult() {
    repository.insert(payment(1L, 7L, PaymentStatus.SUCCESS));

    assertAll(
      // A well-formed id that nobody has.
      () -> assertTrue(repository.findById(new ObjectId().toHexString()).isEmpty()),
      // Not an id at all.
      () -> assertTrue(repository.findById("not-an-id").isEmpty()));
  }

  @Test
  void aSecondSuccessfulPaymentOfTheSameOrderIsRefusedByTheDatabase() {
    repository.insert(payment(1L, 7L, PaymentStatus.SUCCESS));

    DuplicateKeyException refused = assertThrows(DuplicateKeyException.class,
      () -> repository.insert(payment(1L, 8L, PaymentStatus.SUCCESS)));

    assertAll(
      // Pins the format of the message of MongoDB 8: the name is cut out of its text.
      () -> assertEquals(ViolatedIndex.UNIQUE_ORDER_SUCCESS, ViolatedIndex.nameOf(refused)),
      () -> assertEquals(1, countPayments()));
  }

  @Test
  void anOrderCanHaveManyFailedPaymentsBeforeAndAfterTheSuccessfulOne() {
    repository.insert(payment(1L, 7L, PaymentStatus.FAILED));
    repository.insert(payment(1L, 7L, PaymentStatus.FAILED));
    repository.insert(payment(1L, 7L, PaymentStatus.SUCCESS));
    repository.insert(payment(1L, 7L, PaymentStatus.FAILED));

    assertEquals(4, countPayments());
  }

  @Test
  void differentOrdersCanEachHaveASuccessfulPayment() {
    repository.insert(payment(1L, 7L, PaymentStatus.SUCCESS));
    repository.insert(payment(2L, 7L, PaymentStatus.SUCCESS));

    assertEquals(2, countPayments());
  }

  @Test
  void insertNeverReplacesASavedPayment() {
    Payment saved = repository.insert(payment(1L, 7L, PaymentStatus.FAILED));

    // The same object again: it has an id now, and an insert with a known id must not overwrite.
    DuplicateKeyException refused =
      assertThrows(DuplicateKeyException.class, () -> repository.insert(saved));

    assertAll(
      () -> assertEquals("_id_", ViolatedIndex.nameOf(refused)),
      () -> assertEquals(1, countPayments()));
  }

  @Test
  void aMissingPaymentIsRefused() {
    assertThrows(NullPointerException.class, () -> repository.insert(null));
  }

  @Test
  void theRepositoryOffersNeitherSaveNorDelete() {
    List<String> methods = Arrays.stream(PaymentRepository.class.getMethods())
      .map(Method::getName)
      .toList();

    assertAll(
      () -> assertFalse(methods.contains("save")),
      () -> assertFalse(methods.contains("saveAll")),
      () -> assertFalse(methods.contains("delete")),
      () -> assertFalse(methods.contains("deleteById")),
      () -> assertFalse(methods.contains("deleteAll")),
      () -> assertTrue(methods.contains("insert")),
      () -> assertTrue(methods.contains("findById")));
  }

  @RepeatedTest(5)
  void ofEightParallelSuccessfulPaymentsOfOneOrderExactlyOneIsSaved() throws Exception {
    int threads = 8;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      // Each task returns null when its payment was saved, otherwise the exception.
      Callable<Throwable> task = () -> {
        barrier.await(10, TimeUnit.SECONDS);
        try {
          repository.insert(payment(1L, 7L, PaymentStatus.SUCCESS));
          return null;
        } catch (RuntimeException e) {
          return e;
        }
      };
      List<Future<Throwable>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(pool.submit(task));
      }
      List<Throwable> failures = new ArrayList<>();
      for (Future<Throwable> result : results) {
        Throwable failure = result.get(30, TimeUnit.SECONDS);
        if (failure != null) {
          failures.add(failure);
        }
      }

      assertAll(
        () -> assertEquals(threads - 1, failures.size()),
        () -> assertTrue(failures.stream().allMatch(DuplicateKeyException.class::isInstance)),
        () -> assertTrue(failures.stream().allMatch(
          failure -> ViolatedIndex.UNIQUE_ORDER_SUCCESS.equals(ViolatedIndex.nameOf(failure)))),
        () -> assertEquals(1, countPayments()));
    } finally {
      pool.shutdownNow();
    }
  }
}
