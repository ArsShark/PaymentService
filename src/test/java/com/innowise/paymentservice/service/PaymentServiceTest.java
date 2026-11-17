package com.innowise.paymentservice.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.innowise.paymentservice.TestPayments;
import com.innowise.paymentservice.client.RandomNumberClient;
import com.innowise.paymentservice.dto.request.PaymentRequest;
import com.innowise.paymentservice.dto.response.PaymentResponse;
import com.innowise.paymentservice.dto.response.PaymentTotalResponse;
import com.innowise.paymentservice.entity.Payment;
import com.innowise.paymentservice.entity.PaymentStatus;
import com.innowise.paymentservice.exception.InvalidPeriodException;
import com.innowise.paymentservice.exception.OrderAlreadyPaidException;
import com.innowise.paymentservice.exception.PaymentNotFoundException;
import com.innowise.paymentservice.exception.RandomNumberTimeoutException;
import com.innowise.paymentservice.exception.RandomNumberUnavailableException;
import com.innowise.paymentservice.mapper.PaymentMapperImpl;
import com.innowise.paymentservice.messaging.PaymentEventPublisher;
import com.innowise.paymentservice.repository.PaymentFilter;
import com.innowise.paymentservice.repository.PaymentRepository;
import com.innowise.paymentservice.repository.PaymentTotal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests of {@link PaymentService} with plain Mockito: no Spring context, no database, no
 * network. The repository and the client of the random number service are mocks; the mapper is
 * the real generated class (it has its own tests); the clock is fixed. Strict stubs: a stubbing
 * that the service never uses fails the test.
 *
 * <p>The order id (5), the payer (7) and the other user (8) differ on purpose, so mixing them up
 * fails a test. How the service behaves against a real MongoDB, a real HTTP server and Kafka is
 * proved by the integration tests of JACAG-26.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

  private static final long ORDER_ID = 5L;
  private static final String PAYMENT_ID = "65f0c0ffee0123456789abcd";

  /** The clock stands at a time with microseconds: the stored time is cut to milliseconds. */
  private static final Instant NOW = Instant.parse("2026-11-15T12:00:00.123456Z");

  private static final Caller PAYER = new Caller(7L, false);
  private static final Caller STRANGER = new Caller(8L, false);
  private static final Caller ADMIN = new Caller(1L, true);

  private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant BEFORE = Instant.parse("2026-12-01T00:00:00Z");

  private static final String UNIQUE_ORDER_SUCCESS = "uq_payments_order_id_success";

  @Mock
  private PaymentRepository paymentRepository;

  @Mock
  private RandomNumberClient randomNumberClient;

  @Mock
  private PaymentEventPublisher eventPublisher;

  private PaymentService service;

  @BeforeEach
  void createService() {
    service = new PaymentService(paymentRepository, randomNumberClient, new PaymentMapperImpl(),
      eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  /** The error of MongoDB for a violated unique index, with the text that names the index. */
  private static DuplicateKeyException duplicateKey(String index) {
    return new DuplicateKeyException(String.format(
      "E11000 duplicate key error collection: payment_service_db.payments index: %s dup key: { }",
      index));
  }

  private static PaymentRequest request(String amount) {
    return new PaymentRequest(ORDER_ID, new BigDecimal(amount));
  }

  private static Payment stored(long userId) {
    return TestPayments.saved(PAYMENT_ID, ORDER_ID, userId, PaymentStatus.SUCCESS,
      Instant.parse("2026-11-15T12:00:00.123Z"), "24.00");
  }

  /** Makes the repository behave like MongoDB: the saved payment comes back with its id. */
  private void stubInsertAssigningAnId() {
    when(paymentRepository.insert(any())).thenAnswer(invocation -> {
      Payment payment = invocation.getArgument(0);
      ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
      return payment;
    });
  }

  private void stubFreeOrderAndNumber(long number) {
    when(paymentRepository.hasSuccessfulPayment(ORDER_ID)).thenReturn(false);
    when(randomNumberClient.nextNumber()).thenReturn(number);
  }

  // ---- create

  @ParameterizedTest
  @CsvSource({"42,SUCCESS", "2,SUCCESS", "0,SUCCESS", "-4,SUCCESS",
    "7,FAILED", "1,FAILED", "99,FAILED", "-3,FAILED"})
  void theParityOfTheNumberDecidesTheStatus(long number, PaymentStatus expected) {
    stubFreeOrderAndNumber(number);
    stubInsertAssigningAnId();

    PaymentResponse response = service.create(PAYER, request("24.00"));

    assertEquals(expected, response.status());
  }

  @Test
  void createSavesThePayerFromTheCallerTheTimeFromTheClockAndTheAmountWithTwoDecimals() {
    stubFreeOrderAndNumber(42L);
    stubInsertAssigningAnId();

    PaymentResponse response = service.create(PAYER, request("24"));

    ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).insert(captor.capture());
    Payment saved = captor.getValue();
    assertAll(
      () -> assertEquals(ORDER_ID, saved.getOrderId()),
      () -> assertEquals(7L, saved.getUserId()),
      () -> assertEquals(PaymentStatus.SUCCESS, saved.getStatus()),
      () -> assertEquals(Instant.parse("2026-11-15T12:00:00.123Z"), saved.getTimestamp()),
      () -> assertEquals(new BigDecimal("24.00"), saved.getPaymentAmount()),
      () -> assertEquals(PAYMENT_ID, response.id()),
      () -> assertEquals(ORDER_ID, response.orderId()),
      () -> assertEquals(7L, response.userId()),
      () -> assertEquals(PaymentStatus.SUCCESS, response.status()),
      () -> assertEquals(Instant.parse("2026-11-15T12:00:00.123Z"), response.timestamp()),
      () -> assertEquals(new BigDecimal("24.00"), response.amount()));
  }

  @Test
  void theEventIsSentAfterTheInsertAndCarriesTheSavedPayment() {
    stubFreeOrderAndNumber(42L);
    stubInsertAssigningAnId();

    service.create(PAYER, request("24.00"));

    InOrder order = inOrder(paymentRepository, eventPublisher);
    ArgumentCaptor<Payment> sent = ArgumentCaptor.forClass(Payment.class);
    order.verify(paymentRepository).insert(any());
    order.verify(eventPublisher).publish(sent.capture());
    assertAll(
      () -> assertEquals(PAYMENT_ID, sent.getValue().getId()),
      () -> assertEquals(ORDER_ID, sent.getValue().getOrderId()),
      () -> assertEquals(7L, sent.getValue().getUserId()),
      () -> assertEquals(PaymentStatus.SUCCESS, sent.getValue().getStatus()));
  }

  @Test
  void aDeclinedPaymentSendsItsEventToo() {
    stubFreeOrderAndNumber(7L);
    stubInsertAssigningAnId();

    service.create(PAYER, request("24.00"));

    ArgumentCaptor<Payment> sent = ArgumentCaptor.forClass(Payment.class);
    verify(eventPublisher).publish(sent.capture());
    assertEquals(PaymentStatus.FAILED, sent.getValue().getStatus());
  }

  @Test
  void anAdminWithAProfilePaysAsHimself() {
    stubFreeOrderAndNumber(3L);
    stubInsertAssigningAnId();

    PaymentResponse response = service.create(ADMIN, request("10.00"));

    assertAll(
      () -> assertEquals(1L, response.userId()),
      () -> assertEquals(PaymentStatus.FAILED, response.status()));
  }

  @Test
  void aCallerWithoutAProfileCannotPayAndNothingIsAskedOrSaved() {
    assertThrows(NullPointerException.class,
      () -> service.create(new Caller(null, true), request("24.00")));

    verifyNoInteractions(paymentRepository, randomNumberClient, eventPublisher);
  }

  @Test
  void anOrderThatIsAlreadyPaidIsRefusedBeforeTheRandomNumberServiceIsAsked() {
    when(paymentRepository.hasSuccessfulPayment(ORDER_ID)).thenReturn(true);

    OrderAlreadyPaidException thrown = assertThrows(OrderAlreadyPaidException.class,
      () -> service.create(PAYER, request("24.00")));

    assertAll(
      () -> assertEquals("Order 5 is already paid", thrown.getMessage()),
      () -> verifyNoInteractions(randomNumberClient),
      () -> verify(paymentRepository, never()).insert(any()),
      () -> verifyNoInteractions(eventPublisher));
  }

  @Test
  void aRaceLostToTheUniqueIndexIsTheSameRefusalAndDoesNotRevealTheDocument() {
    stubFreeOrderAndNumber(42L);
    when(paymentRepository.insert(any())).thenThrow(duplicateKey(UNIQUE_ORDER_SUCCESS));

    OrderAlreadyPaidException thrown = assertThrows(OrderAlreadyPaidException.class,
      () -> service.create(PAYER, request("24.00")));

    assertAll(
      () -> assertEquals("Order 5 is already paid", thrown.getMessage()),
      () -> assertFalse(thrown.getMessage().contains("dup key")),
      () -> assertNull(thrown.getCause()),
      () -> verifyNoInteractions(eventPublisher));
  }

  @Test
  void aDuplicateKeyOfAnotherIndexIsABugAndIsPassedOnUnchanged() {
    stubFreeOrderAndNumber(42L);
    DuplicateKeyException other = duplicateKey("_id_");
    when(paymentRepository.insert(any())).thenThrow(other);

    DuplicateKeyException thrown = assertThrows(DuplicateKeyException.class,
      () -> service.create(PAYER, request("24.00")));

    assertAll(
      () -> assertSame(other, thrown),
      () -> verifyNoInteractions(eventPublisher));
  }

  @Test
  void aDuplicateKeyThatNamesNoIndexIsPassedOnUnchanged() {
    stubFreeOrderAndNumber(42L);
    DuplicateKeyException unknown = new DuplicateKeyException("something else went wrong");
    when(paymentRepository.insert(any())).thenThrow(unknown);

    assertSame(unknown, assertThrows(DuplicateKeyException.class,
      () -> service.create(PAYER, request("24.00"))));
  }

  @Test
  void whenTheRandomNumberServiceIsNotAvailableNothingIsSaved() {
    when(paymentRepository.hasSuccessfulPayment(ORDER_ID)).thenReturn(false);
    RandomNumberUnavailableException failure = new RandomNumberUnavailableException();
    when(randomNumberClient.nextNumber()).thenThrow(failure);

    assertSame(failure, assertThrows(RandomNumberUnavailableException.class,
      () -> service.create(PAYER, request("24.00"))));
    assertAll(
      () -> verify(paymentRepository, never()).insert(any()),
      () -> verifyNoInteractions(eventPublisher));
  }

  @Test
  void whenTheRandomNumberServiceIsTooSlowNothingIsSaved() {
    when(paymentRepository.hasSuccessfulPayment(ORDER_ID)).thenReturn(false);
    RandomNumberTimeoutException failure = new RandomNumberTimeoutException();
    when(randomNumberClient.nextNumber()).thenThrow(failure);

    assertSame(failure, assertThrows(RandomNumberTimeoutException.class,
      () -> service.create(PAYER, request("24.00"))));
    assertAll(
      () -> verify(paymentRepository, never()).insert(any()),
      () -> verifyNoInteractions(eventPublisher));
  }

  // ---- getById

  @Test
  void thePayerSeesHisPayment() {
    when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(stored(7L)));

    PaymentResponse response = service.getById(PAYMENT_ID, PAYER);

    assertAll(
      () -> assertEquals(PAYMENT_ID, response.id()),
      () -> assertEquals(7L, response.userId()),
      () -> assertEquals(ORDER_ID, response.orderId()));
  }

  @Test
  void anAdminSeesAnyPayment() {
    when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(stored(7L)));

    assertEquals(PAYMENT_ID, service.getById(PAYMENT_ID, ADMIN).id());
  }

  @Test
  void aPaymentOfSomebodyElseIsTheSameNotFoundAsAMissingOne() {
    when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(stored(7L)));
    when(paymentRepository.findById("missing")).thenReturn(Optional.empty());

    PaymentNotFoundException foreign = assertThrows(PaymentNotFoundException.class,
      () -> service.getById(PAYMENT_ID, STRANGER));
    PaymentNotFoundException missing = assertThrows(PaymentNotFoundException.class,
      () -> service.getById("missing", STRANGER));

    assertAll(
      () -> assertEquals(missing.getMessage(), foreign.getMessage()),
      () -> assertFalse(foreign.getMessage().contains(PAYMENT_ID)),
      () -> assertFalse(missing.getMessage().contains("missing")));
  }

  // ---- lists

  @Test
  void findAllPassesTheFilterAndThePageOnAndMapsTheContent() {
    PaymentFilter filter = new PaymentFilter(7L, null, PaymentStatus.SUCCESS);
    Pageable pageable = PageRequest.of(1, 20);
    when(paymentRepository.findAll(filter, pageable))
      .thenReturn(new PageImpl<>(List.of(stored(7L)), pageable, 41));

    Page<PaymentResponse> page = service.findAll(filter, pageable);

    assertAll(
      () -> assertEquals(41, page.getTotalElements()),
      () -> assertEquals(1, page.getNumber()),
      () -> assertEquals(List.of(PAYMENT_ID),
        page.getContent().stream().map(PaymentResponse::id).toList()));
  }

  @Test
  void findOwnForcesTheOwnerWhateverTheFilterSaysAndKeepsTheOtherConditions() {
    // The caller (user 7) asks for the payments of user 99. The stub accepts only the filter
    // with his own id: a service that passed 99 on would fail with a stubbing problem.
    PaymentFilter asked = new PaymentFilter(99L, 3L, PaymentStatus.FAILED);
    Pageable pageable = PageRequest.of(0, 20);
    when(paymentRepository.findAll(new PaymentFilter(7L, 3L, PaymentStatus.FAILED), pageable))
      .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    Page<PaymentResponse> page = service.findOwn(7L, asked, pageable);

    assertEquals(0, page.getTotalElements());
  }

  @Test
  void findOwnWithoutAFilterListsOnlyThePaymentsOfTheOwner() {
    Pageable pageable = PageRequest.of(0, 20);
    when(paymentRepository.findAll(PaymentFilter.any().withUserId(7L), pageable))
      .thenReturn(new PageImpl<>(List.of(stored(7L)), pageable, 1));

    assertEquals(1, service.findOwn(7L, PaymentFilter.any(), pageable).getTotalElements());
  }

  // ---- totals

  @Test
  void theTotalOfAUserIsTheSumOfHisPaymentsInThePeriod() {
    when(paymentRepository.sumOfUser(7L, FROM, BEFORE))
      .thenReturn(new PaymentTotal(new BigDecimal("124.00"), 3L));

    PaymentTotalResponse response = service.totalOfUser(7L, FROM, BEFORE);

    assertAll(
      () -> assertEquals(new BigDecimal("124.00"), response.total()),
      () -> assertEquals(3L, response.count()),
      () -> assertEquals(FROM, response.from()),
      () -> assertEquals(BEFORE, response.before()));
  }

  @Test
  void theTotalOfAllUsersIsTheSumOfEveryPaymentInThePeriod() {
    when(paymentRepository.sumOfAll(FROM, BEFORE))
      .thenReturn(new PaymentTotal(new BigDecimal("999.50"), 12L));

    PaymentTotalResponse response = service.totalOfAll(FROM, BEFORE);

    assertAll(
      () -> assertEquals(new BigDecimal("999.50"), response.total()),
      () -> assertEquals(12L, response.count()),
      () -> assertEquals(FROM, response.from()),
      () -> assertEquals(BEFORE, response.before()));
  }

  @Test
  void aPeriodWithNoPaymentsIsZero() {
    when(paymentRepository.sumOfAll(FROM, BEFORE))
      .thenReturn(new PaymentTotal(new BigDecimal("0.00"), 0L));

    PaymentTotalResponse response = service.totalOfAll(FROM, BEFORE);

    assertAll(
      () -> assertEquals(new BigDecimal("0.00"), response.total()),
      () -> assertEquals(0L, response.count()));
  }

  @Test
  void theBoundsAreCutToMillisecondsAndTheCutBoundsAreCountedAndReported() {
    Instant from = Instant.parse("2026-11-01T00:00:00.0019Z");
    Instant before = Instant.parse("2026-11-02T00:00:00.0027Z");
    Instant cutFrom = Instant.parse("2026-11-01T00:00:00.001Z");
    Instant cutBefore = Instant.parse("2026-11-02T00:00:00.002Z");
    when(paymentRepository.sumOfUser(7L, cutFrom, cutBefore))
      .thenReturn(new PaymentTotal(new BigDecimal("5.00"), 1L));

    PaymentTotalResponse response = service.totalOfUser(7L, from, before);

    assertAll(
      () -> assertEquals(cutFrom, response.from()),
      () -> assertEquals(cutBefore, response.before()));
  }

  static Stream<Arguments> periodsThatAreEmptyOrReversed() {
    return Stream.of(
      Arguments.of(FROM, FROM),
      Arguments.of(BEFORE, FROM),
      // Different instants, but the same millisecond: empty for the database.
      Arguments.of(Instant.parse("2026-11-01T00:00:00.0004Z"),
        Instant.parse("2026-11-01T00:00:00.0009Z")));
  }

  @ParameterizedTest
  @MethodSource("periodsThatAreEmptyOrReversed")
  void aPeriodThatIsEmptyOrReversedIsRefusedAndTheDatabaseIsNotAsked(Instant from,
    Instant before) {
    assertAll(
      () -> assertEquals(InvalidPeriodException.MESSAGE, assertThrows(
        InvalidPeriodException.class, () -> service.totalOfUser(7L, from, before)).getMessage()),
      () -> assertThrows(InvalidPeriodException.class, () -> service.totalOfAll(from, before)),
      () -> verifyNoInteractions(paymentRepository));
  }

  @Test
  void aMissingBoundIsRefused() {
    assertAll(
      () -> assertThrows(NullPointerException.class, () -> service.totalOfAll(null, BEFORE)),
      () -> assertThrows(NullPointerException.class, () -> service.totalOfAll(FROM, null)),
      () -> assertThrows(NullPointerException.class,
        () -> service.totalOfUser(7L, null, BEFORE)),
      () -> verifyNoInteractions(paymentRepository));
  }
}
